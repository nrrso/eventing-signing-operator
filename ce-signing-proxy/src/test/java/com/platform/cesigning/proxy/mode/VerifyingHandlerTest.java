// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.mode;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventSigner;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import com.platform.cesigning.proxy.registry.PublicKeyEntry;
import com.platform.cesigning.proxy.registry.RegistryKeyCache;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for VerifyingHandler, instantiated directly without CDI. */
class VerifyingHandlerTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;
    private static final String KEY_ID = "bu-alice-v1";
    private static final String CLUSTER = "cluster-east";
    private static final String NAMESPACE = "bu-alice";
    private static final List<String> CANONICAL_ATTRS;
    private static final Set<String> TRUSTED_SOURCES = Set.of("cluster-east/bu-alice");

    static {
        List<String> attrs =
                new ArrayList<>(List.of("type", "source", "subject", "datacontenttype"));
        attrs.add("cesignercluster");
        CANONICAL_ATTRS = List.copyOf(attrs);
    }

    private RegistryKeyCache keyCache;
    private VerifyingHandler handler;

    @BeforeAll
    static void setUpClass() throws IOException {
        signer = new EventSigner(Path.of("src/test/resources/test-private.pem"));
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    @BeforeEach
    void setUp() {
        keyCache = new RegistryKeyCache();
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

        handler = makeHandler(true);
    }

    @Test
    void validSignedEventReturnsOk() {
        try (Response response = handler.verify(signEvent(buildTestEvent()))) {
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void validEventRetainsAllFiveSignatureExtensions() {
        CloudEvent signed = signEvent(buildTestEvent());
        try (Response response = handler.verify(signed)) {
            assertEquals(200, response.getStatus());
        }
        assertNotNull(signed.getExtension("cesignature"));
        assertNotNull(signed.getExtension("cesignaturealg"));
        assertNotNull(signed.getExtension("cekeyid"));
        assertNotNull(signed.getExtension("cecanonattrs"));
        assertNotNull(signed.getExtension("cesignercluster"));
    }

    @Test
    void invalidSignatureReturns403() {
        CloudEvent event = buildTestEvent();
        CloudEvent badSigned =
                CloudEventBuilder.from(Objects.requireNonNull(event))
                        .withExtension(
                                "cesignature",
                                Objects.requireNonNull(
                                        Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(new byte[64])))
                        .withExtension("cesignaturealg", "ed25519")
                        .withExtension("cekeyid", KEY_ID)
                        .withExtension("cecanonattrs", "source,type")
                        .withExtension("cesignercluster", CLUSTER)
                        .build();

        try (Response response = handler.verify(badSigned)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void unsignedEventRejectedWhenRejectUnsignedTrue() {
        try (Response response = handler.verify(buildTestEvent())) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void unsignedEventPassesThroughWhenRejectUnsignedFalse() {
        VerifyingHandler allowUnsigned = makeHandler(false);
        try (Response response = allowUnsigned.verify(buildTestEvent())) {
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void partialExtensionsTreatedAsUnsigned() {
        CloudEvent partial =
                CloudEventBuilder.from(Objects.requireNonNull(buildTestEvent()))
                        .withExtension("cesignature", "dummysig")
                        .withExtension("cesignaturealg", "ed25519")
                        .build();

        try (Response response = handler.verify(partial)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void unknownKeyIdReturns403() {
        CloudEvent signed = signEventWithKeyId(buildTestEvent(), "unknown-key-v1");
        try (Response response = handler.verify(signed)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void untrustedSourceReturns403() {
        String untrustedCluster = "cluster-west";
        PublicKeyEntry untrustedEntry =
                new PublicKeyEntry(
                        untrustedCluster,
                        "bu-eve",
                        "bu-eve-v1",
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        keyCache.put(new RegistryKeyCache.CacheKey(untrustedCluster, "bu-eve-v1"), untrustedEntry);

        CloudEvent signed =
                signEventWithKeyIdAndCluster(buildTestEvent(), "bu-eve-v1", untrustedCluster);
        try (Response response = handler.verify(signed)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void sameKeyIdDifferentClustersResolveCorrectly() {
        // Add same keyId from a different cluster
        String otherCluster = "cluster-west";
        Ed25519PublicKeyParameters otherKey = publicKey; // same key for test simplicity
        PublicKeyEntry otherEntry =
                new PublicKeyEntry(
                        otherCluster,
                        "bu-alice",
                        KEY_ID,
                        otherKey,
                        "ed25519",
                        OffsetDateTime.now(),
                        OffsetDateTime.now().plusDays(90),
                        "active");
        keyCache.put(new RegistryKeyCache.CacheKey(otherCluster, KEY_ID), otherEntry);

        // Both clusters have KEY_ID; verify cache distinguishes them
        assertTrue(keyCache.getEntry(CLUSTER, KEY_ID).isPresent());
        assertTrue(keyCache.getEntry(otherCluster, KEY_ID).isPresent());
        assertEquals(CLUSTER, keyCache.getEntry(CLUSTER, KEY_ID).get().cluster());
        assertEquals(otherCluster, keyCache.getEntry(otherCluster, KEY_ID).get().cluster());
    }

    @Test
    void expiredKeyReturns403() {
        PublicKeyEntry expired =
                new PublicKeyEntry(
                        CLUSTER,
                        NAMESPACE,
                        "expired-key",
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now().minusDays(100),
                        OffsetDateTime.now().minusDays(10),
                        "expired");
        keyCache.put(new RegistryKeyCache.CacheKey(CLUSTER, "expired-key"), expired);

        CloudEvent signed = signEventWithKeyId(buildTestEvent(), "expired-key");
        try (Response response = handler.verify(signed)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void rotatingKeyAccepted() {
        PublicKeyEntry rotating =
                new PublicKeyEntry(
                        CLUSTER,
                        NAMESPACE,
                        "rotating-key",
                        publicKey,
                        "ed25519",
                        OffsetDateTime.now().minusDays(90),
                        OffsetDateTime.now().plusDays(7),
                        "rotating");
        keyCache.put(new RegistryKeyCache.CacheKey(CLUSTER, "rotating-key"), rotating);

        CloudEvent signed = signEventWithKeyId(buildTestEvent(), "rotating-key");
        try (Response response = handler.verify(signed)) {
            assertEquals(200, response.getStatus());
        }
    }

    @Test
    void unsupportedAlgorithmReturns403() {
        CloudEvent event = buildTestEvent();
        CloudEvent wrongAlg =
                CloudEventBuilder.from(Objects.requireNonNull(event))
                        .withExtension(
                                "cesignature",
                                Objects.requireNonNull(
                                        Base64.getUrlEncoder()
                                                .withoutPadding()
                                                .encodeToString(new byte[64])))
                        .withExtension("cesignaturealg", "rsa256")
                        .withExtension("cekeyid", KEY_ID)
                        .withExtension("cecanonattrs", "source,type")
                        .withExtension("cesignercluster", CLUSTER)
                        .build();

        try (Response response = handler.verify(wrongAlg)) {
            assertEquals(403, response.getStatus());
        }
    }

    @Test
    void fiveExtensionCompletenessCheck() {
        // Missing cesignercluster should be treated as unsigned
        CloudEvent missingCluster =
                CloudEventBuilder.from(Objects.requireNonNull(buildTestEvent()))
                        .withExtension("cesignature", "dummysig")
                        .withExtension("cesignaturealg", "ed25519")
                        .withExtension("cekeyid", KEY_ID)
                        .withExtension("cecanonattrs", "source,type")
                        .build();

        try (Response response = handler.verify(missingCluster)) {
            assertEquals(403, response.getStatus());
        }
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

    private CloudEvent signEvent(CloudEvent event) {
        return signEventWithKeyIdAndCluster(event, KEY_ID, CLUSTER);
    }

    private CloudEvent signEventWithKeyId(CloudEvent event, String keyId) {
        return signEventWithKeyIdAndCluster(event, keyId, CLUSTER);
    }

    private CloudEvent signEventWithKeyIdAndCluster(
            CloudEvent event, String keyId, String cluster) {
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

    private VerifyingHandler makeHandler(boolean rejectUnsigned) {
        VerifyingHandler h = new VerifyingHandler();
        h.config = makeConfig(rejectUnsigned);
        h.keyCache = keyCache;
        h.meterRegistry = new SimpleMeterRegistry();
        h.tracer = OpenTelemetry.noop().getTracer("test");
        h.init();
        return h;
    }

    private ProxyConfig makeConfig(boolean rejectUnsigned) {
        return new ProxyConfig() {
            @Override
            public String mode() {
                return "verify";
            }

            @Override
            public String privateKeyPath() {
                return "";
            }

            @Override
            public String keyId() {
                return "";
            }

            @Override
            public String clusterName() {
                return CLUSTER;
            }

            @Override
            public List<String> canonicalAttributes() {
                return CANONICAL_ATTRS;
            }

            @Override
            public VerifyConfig verify() {
                return new VerifyConfig() {
                    @Override
                    public Optional<List<String>> trustedSources() {
                        return Optional.of(new ArrayList<>(TRUSTED_SOURCES));
                    }

                    @Override
                    public boolean rejectUnsigned() {
                        return rejectUnsigned;
                    }
                };
            }
        };
    }
}
