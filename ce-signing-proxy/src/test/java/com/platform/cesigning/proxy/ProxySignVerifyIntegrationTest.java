// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventSigner;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import com.platform.cesigning.proxy.registry.PublicKeyEntry;
import com.platform.cesigning.proxy.registry.RegistryKeyCache;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Integration test: sign a CloudEvent with cesignercluster, then verify it with cluster-aware
 * lookup, simulating the full proxy roundtrip (signer -> broker -> verifier).
 */
class ProxySignVerifyIntegrationTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;

    private static final String KEY_ID = "bu-alice-v1";
    private static final String CLUSTER = "cluster-east";
    private static final String NAMESPACE = "bu-alice";
    private static final List<String> CANONICAL_ATTRS;

    static {
        List<String> attrs =
                new ArrayList<>(List.of("type", "source", "subject", "datacontenttype"));
        attrs.add("cesignercluster");
        CANONICAL_ATTRS = List.copyOf(attrs);
    }

    @BeforeAll
    static void setUp() throws IOException {
        signer = new EventSigner(Path.of("src/test/resources/test-private.pem"));
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    /** 10.1: Full sign-verify round-trip with cesignercluster. */
    @Test
    void fullSignVerifyRoundTripWithClusterIdentity() {
        CloudEvent original =
                CloudEventBuilder.v1()
                        .withId("order-001")
                        .withSource(URI.create("/bu-alice/orders"))
                        .withType("order.created")
                        .withSubject("order-12345")
                        .withDataContentType("application/json")
                        .withData(
                                "application/json",
                                "{\"orderId\":\"12345\",\"amount\":99.99,\"currency\":\"USD\"}"
                                        .getBytes(StandardCharsets.UTF_8))
                        .build();

        // Sign with cluster identity
        CloudEvent signedEvent = signEvent(original, KEY_ID, CLUSTER);

        // Verify all 5 extensions present
        assertNotNull(signedEvent.getExtension("cesignature"));
        assertNotNull(signedEvent.getExtension("cesignaturealg"));
        assertNotNull(signedEvent.getExtension("cekeyid"));
        assertNotNull(signedEvent.getExtension("cecanonattrs"));
        assertNotNull(signedEvent.getExtension("cesignercluster"));
        assertEquals(CLUSTER, signedEvent.getExtension("cesignercluster").toString());

        // Set up verifier's key cache with composite key
        RegistryKeyCache keyCache = new RegistryKeyCache();
        PublicKeyEntry entry =
                new PublicKeyEntry(
                        CLUSTER,
                        NAMESPACE,
                        KEY_ID,
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        keyCache.replaceAll(Map.of(new RegistryKeyCache.CacheKey(CLUSTER, KEY_ID), entry));

        // Verify with cluster-aware lookup
        String cesignercluster = signedEvent.getExtension("cesignercluster").toString();
        String cekeyid = signedEvent.getExtension("cekeyid").toString();
        Optional<PublicKeyEntry> lookedUp = keyCache.getEntry(cesignercluster, cekeyid);
        assertTrue(lookedUp.isPresent());
        assertTrue(lookedUp.get().isUsableForVerification());
        assertEquals(CLUSTER, lookedUp.get().cluster());
        assertEquals(NAMESPACE, lookedUp.get().namespace());

        // Rebuild canonical form and verify signature
        String cecanonattrs = signedEvent.getExtension("cecanonattrs").toString();
        List<String> verifyAttrs = Arrays.asList(cecanonattrs.split(","));
        byte[] verifyCanonical = CanonicalForm.build(signedEvent, verifyAttrs);
        byte[] sigBytes =
                Base64.getUrlDecoder().decode(signedEvent.getExtension("cesignature").toString());

        assertTrue(
                EventVerifier.verify(verifyCanonical, sigBytes, lookedUp.get().publicKey()),
                "Signature should verify successfully with cluster-aware lookup");

        assertEquals("order.created", signedEvent.getType());
        assertArrayEquals(original.getData().toBytes(), signedEvent.getData().toBytes());
    }

    /** 10.2: Same keyId from different clusters resolves to correct key. */
    @Test
    void sameKeyIdDifferentClustersResolvesCorrectly() {
        String clusterEast = "cluster-east";
        String clusterWest = "cluster-west";

        RegistryKeyCache keyCache = new RegistryKeyCache();
        PublicKeyEntry eastEntry =
                new PublicKeyEntry(
                        clusterEast,
                        "bu-alice",
                        KEY_ID,
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        PublicKeyEntry westEntry =
                new PublicKeyEntry(
                        clusterWest,
                        "bu-alice",
                        KEY_ID,
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        keyCache.replaceAll(
                Map.of(
                        new RegistryKeyCache.CacheKey(clusterEast, KEY_ID), eastEntry,
                        new RegistryKeyCache.CacheKey(clusterWest, KEY_ID), westEntry));

        // Sign from east
        CloudEvent fromEast = signEvent(buildTestEvent(), KEY_ID, clusterEast);
        // Sign from west
        CloudEvent fromWest = signEvent(buildTestEvent(), KEY_ID, clusterWest);

        // Lookup resolves to correct cluster entry
        String eastCluster = fromEast.getExtension("cesignercluster").toString();
        String westCluster = fromWest.getExtension("cesignercluster").toString();

        Optional<PublicKeyEntry> eastResult = keyCache.getEntry(eastCluster, KEY_ID);
        Optional<PublicKeyEntry> westResult = keyCache.getEntry(westCluster, KEY_ID);

        assertTrue(eastResult.isPresent());
        assertTrue(westResult.isPresent());
        assertEquals(clusterEast, eastResult.get().cluster());
        assertEquals(clusterWest, westResult.get().cluster());

        // Both verify successfully
        assertTrue(verifySignedEvent(fromEast, eastResult.get()));
        assertTrue(verifySignedEvent(fromWest, westResult.get()));
    }

    /**
     * 10.3: Trust filtering with trustedSources — verifies that the composite cache key model
     * supports (cluster, namespace) pair-based trust. Events from untrusted pairs won't find
     * matching entries in a correctly configured cache.
     */
    @Test
    void trustFilteringWithTrustedSources() {
        RegistryKeyCache keyCache = new RegistryKeyCache();

        // Only trust cluster-east/bu-alice
        PublicKeyEntry trustedEntry =
                new PublicKeyEntry(
                        "cluster-east",
                        "bu-alice",
                        KEY_ID,
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        keyCache.replaceAll(
                Map.of(new RegistryKeyCache.CacheKey("cluster-east", KEY_ID), trustedEntry));

        // Trusted (cluster, namespace) pair — found
        Optional<PublicKeyEntry> found = keyCache.getEntry("cluster-east", KEY_ID);
        assertTrue(found.isPresent());
        assertEquals("bu-alice", found.get().namespace());
        assertEquals("cluster-east", found.get().cluster());

        // Same keyId from different cluster — not found (untrusted)
        assertTrue(keyCache.getEntry("cluster-west", KEY_ID).isEmpty());

        // Verify event from untrusted cluster can't resolve key
        CloudEvent fromUntrusted = signEvent(buildTestEvent(), KEY_ID, "cluster-west");
        String untrustedCluster = fromUntrusted.getExtension("cesignercluster").toString();
        assertTrue(
                keyCache.getEntry(untrustedCluster, KEY_ID).isEmpty(),
                "Events from untrusted cluster should not resolve to any key");
    }

    /**
     * 10.5: Verifier merges keys from PublicKeyRegistry and FederatedKeyRegistry into unified
     * cache.
     */
    @Test
    void verifierMergesLocalAndFederatedKeysIntoUnifiedCache() {
        RegistryKeyCache keyCache = new RegistryKeyCache();

        // Simulate local keys (from PublicKeyRegistry)
        PublicKeyEntry localEntry =
                new PublicKeyEntry(
                        "cluster-east",
                        "bu-alice",
                        "bu-alice-v1",
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");

        // Simulate federated keys (from FederatedKeyRegistry)
        PublicKeyEntry federatedEntry =
                new PublicKeyEntry(
                        "cluster-west",
                        "bu-bob",
                        "bu-bob-v1",
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");

        // Merge both into cache (as RegistryWatcher would do)
        Map<RegistryKeyCache.CacheKey, PublicKeyEntry> merged = new HashMap<>();
        merged.put(new RegistryKeyCache.CacheKey("cluster-east", "bu-alice-v1"), localEntry);
        merged.put(new RegistryKeyCache.CacheKey("cluster-west", "bu-bob-v1"), federatedEntry);
        keyCache.replaceAll(merged);

        // Both local and federated keys are accessible
        assertTrue(keyCache.getEntry("cluster-east", "bu-alice-v1").isPresent());
        assertTrue(keyCache.getEntry("cluster-west", "bu-bob-v1").isPresent());
        assertEquals(2, keyCache.size());

        // Federated entry has correct cluster
        assertEquals(
                "cluster-west", keyCache.getEntry("cluster-west", "bu-bob-v1").get().cluster());
    }

    @Test
    void signVerifyWithDifferentJsonKeyOrder() {
        CloudEvent original =
                CloudEventBuilder.v1()
                        .withId("evt-1")
                        .withSource(URI.create("/bu-alice"))
                        .withType("test")
                        .withDataContentType("application/json")
                        .withData(
                                "application/json",
                                "{\"b\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8))
                        .build();

        CloudEvent signedEvent = signEvent(original, KEY_ID, CLUSTER);

        // Simulate broker re-serializing JSON with different key order
        CloudEvent reserializedEvent =
                CloudEventBuilder.v1()
                        .withId("evt-1")
                        .withSource(URI.create("/bu-alice"))
                        .withType("test")
                        .withDataContentType("application/json")
                        .withData(
                                "application/json",
                                "{\"a\":2,\"b\":1}".getBytes(StandardCharsets.UTF_8))
                        .withExtension(
                                "cesignature", signedEvent.getExtension("cesignature").toString())
                        .withExtension("cesignaturealg", "ed25519")
                        .withExtension("cekeyid", KEY_ID)
                        .withExtension(
                                "cecanonattrs", signedEvent.getExtension("cecanonattrs").toString())
                        .withExtension("cesignercluster", CLUSTER)
                        .build();

        List<String> attrs =
                Arrays.asList(reserializedEvent.getExtension("cecanonattrs").toString().split(","));
        byte[] verifyCanonical = CanonicalForm.build(reserializedEvent, attrs);
        byte[] sigBytes =
                Base64.getUrlDecoder()
                        .decode(reserializedEvent.getExtension("cesignature").toString());

        assertTrue(
                EventVerifier.verify(verifyCanonical, sigBytes, publicKey),
                "JCS should normalize JSON, allowing verification after re-serialization");
    }

    // --- Helpers ---

    private CloudEvent buildTestEvent() {
        return CloudEventBuilder.v1()
                .withId("test-1")
                .withSource(URI.create("/bu-alice"))
                .withType("order.created")
                .withSubject("order-123")
                .withDataContentType("application/json")
                .withData("application/json", "{\"amount\":42}".getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private CloudEvent signEvent(CloudEvent event, String keyId, String cluster) {
        // Add cluster identity before building canonical form
        CloudEvent eventWithCluster =
                CloudEventBuilder.from(Objects.requireNonNull(event))
                        .withExtension("cesignercluster", cluster)
                        .build();

        byte[] canonical = CanonicalForm.build(eventWithCluster, CANONICAL_ATTRS);
        String signature = signer.signToBase64Url(canonical);
        String presentAttrs = CanonicalForm.presentAttributes(eventWithCluster, CANONICAL_ATTRS);

        return CloudEventBuilder.from(eventWithCluster)
                .withExtension("cesignature", Objects.requireNonNull(signature))
                .withExtension("cesignaturealg", "ed25519")
                .withExtension("cekeyid", Objects.requireNonNull(keyId))
                .withExtension("cecanonattrs", Objects.requireNonNull(presentAttrs))
                .build();
    }

    private boolean verifySignedEvent(CloudEvent signed, PublicKeyEntry entry) {
        String cecanonattrs = signed.getExtension("cecanonattrs").toString();
        List<String> attrs = Arrays.asList(cecanonattrs.split(","));
        byte[] canonical = CanonicalForm.build(signed, attrs);
        byte[] sigBytes =
                Base64.getUrlDecoder().decode(signed.getExtension("cesignature").toString());
        return EventVerifier.verify(canonical, sigBytes, entry.publicKey());
    }
}
