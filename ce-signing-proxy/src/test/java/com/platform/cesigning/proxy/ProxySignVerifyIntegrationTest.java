// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy;

import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventSigner;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import com.platform.cesigning.proxy.registry.PublicKeyEntry;
import com.platform.cesigning.proxy.registry.RegistryKeyCache;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test: sign a CloudEvent, then verify it, simulating the full
 * proxy roundtrip (signer -> broker -> verifier).
 */
class ProxySignVerifyIntegrationTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;

    private static final String KEY_ID = "bu-alice-v1";
    private static final String NAMESPACE = "bu-alice";
    private static final List<String> CANONICAL_ATTRS = List.of("type", "source", "subject", "datacontenttype");

    @BeforeAll
    static void setUp() throws IOException {
        signer = new EventSigner(Path.of("src/test/resources/test-private.pem"));
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    @Test
    void fullSignVerifyRoundTrip() {
        // 1. Create a CloudEvent
        CloudEvent original = CloudEventBuilder.v1()
                .withId("order-001")
                .withSource(URI.create("/bu-alice/orders"))
                .withType("order.created")
                .withSubject("order-12345")
                .withDataContentType("application/json")
                .withData("application/json",
                        "{\"orderId\":\"12345\",\"amount\":99.99,\"currency\":\"USD\"}"
                                .getBytes(StandardCharsets.UTF_8))
                .build();

        // 2. Sign it (signer proxy)
        byte[] canonical = CanonicalForm.build(original, CANONICAL_ATTRS);
        String signature = signer.signToBase64Url(canonical);
        String presentAttrs = CanonicalForm.presentAttributes(original, CANONICAL_ATTRS);

        CloudEvent signedEvent = CloudEventBuilder.from(Objects.requireNonNull(original))
                .withExtension("cesignature", Objects.requireNonNull(signature))
                .withExtension("cesignaturealg", "ed25519")
                .withExtension("cekeyid", KEY_ID)
                .withExtension("cecanonattrs", Objects.requireNonNull(presentAttrs))
                .build();

        // 3. Set up verifier's key cache
        RegistryKeyCache keyCache = new RegistryKeyCache();
        PublicKeyEntry entry = new PublicKeyEntry(
                NAMESPACE, KEY_ID, publicKey, "ed25519",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(90), "active");
        keyCache.replaceAll(Map.of(KEY_ID, entry));

        // 4. Verify (verifier proxy)
        String cecanonattrs = signedEvent.getExtension("cecanonattrs").toString();
        String cesignature = signedEvent.getExtension("cesignature").toString();
        String cekeyid = signedEvent.getExtension("cekeyid").toString();

        Optional<PublicKeyEntry> lookedUp = keyCache.getEntry(cekeyid);
        assertTrue(lookedUp.isPresent());
        assertTrue(lookedUp.get().isUsableForVerification());
        assertEquals(NAMESPACE, lookedUp.get().namespace());

        List<String> verifyAttrs = Arrays.asList(cecanonattrs.split(","));
        byte[] verifyCanonical = CanonicalForm.build(signedEvent, verifyAttrs);
        byte[] sigBytes = Base64.getUrlDecoder().decode(cesignature);

        assertTrue(EventVerifier.verify(verifyCanonical, sigBytes, lookedUp.get().publicKey()),
                "Signature should verify successfully after roundtrip");

        // 5. Verify original data is intact
        assertEquals("order.created", signedEvent.getType());
        assertEquals(URI.create("/bu-alice/orders"), signedEvent.getSource());
        assertArrayEquals(original.getData().toBytes(), signedEvent.getData().toBytes());
    }

    @Test
    void signVerifyWithDifferentJsonKeyOrder() {
        // The broker might re-serialize JSON with different key ordering
        CloudEvent original = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/bu-alice"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{\"b\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8))
                .build();

        // Sign
        byte[] canonical = CanonicalForm.build(original, CANONICAL_ATTRS);
        String signature = signer.signToBase64Url(canonical);
        String presentAttrs = CanonicalForm.presentAttributes(original, CANONICAL_ATTRS);

        // Simulate broker re-serializing JSON with different key order
        CloudEvent reserializedEvent = CloudEventBuilder.v1()
                .withId("evt-1")
                .withSource(URI.create("/bu-alice"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{\"a\":2,\"b\":1}".getBytes(StandardCharsets.UTF_8))
                .withExtension("cesignature", Objects.requireNonNull(signature))
                .withExtension("cesignaturealg", "ed25519")
                .withExtension("cekeyid", KEY_ID)
                .withExtension("cecanonattrs", Objects.requireNonNull(presentAttrs))
                .build();

        // Verify should succeed because JCS normalizes both
        List<String> attrs = Arrays.asList(presentAttrs.split(","));
        byte[] verifyCanonical = CanonicalForm.build(reserializedEvent, attrs);
        byte[] sigBytes = Base64.getUrlDecoder().decode(signature);

        assertTrue(EventVerifier.verify(verifyCanonical, sigBytes, publicKey),
                "JCS should normalize JSON, allowing verification after re-serialization");
    }

    @Test
    void signVerifyWithEmptyData() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("evt-2")
                .withSource(URI.create("/bu-alice"))
                .withType("heartbeat")
                .build();

        byte[] canonical = CanonicalForm.build(event, CANONICAL_ATTRS);
        String signature = signer.signToBase64Url(canonical);
        String presentAttrs = CanonicalForm.presentAttributes(event, CANONICAL_ATTRS);

        CloudEvent signed = CloudEventBuilder.from(Objects.requireNonNull(event))
                .withExtension("cesignature", Objects.requireNonNull(signature))
                .withExtension("cesignaturealg", "ed25519")
                .withExtension("cekeyid", KEY_ID)
                .withExtension("cecanonattrs", Objects.requireNonNull(presentAttrs))
                .build();

        List<String> attrs = Arrays.asList(presentAttrs.split(","));
        byte[] verifyCanonical = CanonicalForm.build(signed, attrs);
        byte[] sigBytes = Base64.getUrlDecoder().decode(signature);

        assertTrue(EventVerifier.verify(verifyCanonical, sigBytes, publicKey));
    }
}
