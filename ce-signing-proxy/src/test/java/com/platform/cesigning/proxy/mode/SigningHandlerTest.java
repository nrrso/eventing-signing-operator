// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.mode;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.proxy.crypto.CanonicalForm;
import com.platform.cesigning.proxy.crypto.EventSigner;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the signing logic (without full CDI/Quarkus context). Tests the core sign flow:
 * canonical form -> sign -> add extensions.
 */
class SigningHandlerTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;
    private static final String KEY_ID = "bu-alice-v1";
    private static final List<String> CANONICAL_ATTRS =
            List.of("type", "source", "subject", "datacontenttype");

    @BeforeAll
    static void setUp() throws IOException {
        signer = new EventSigner(Path.of("src/test/resources/test-private.pem"));
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    @Test
    void signedEventHasAllFourExtensions() {
        CloudEvent event = buildTestEvent();
        CloudEvent signed = signEvent(event);

        assertNotNull(signed.getExtension("cesignature"));
        assertNotNull(signed.getExtension("cesignaturealg"));
        assertNotNull(signed.getExtension("cekeyid"));
        assertNotNull(signed.getExtension("cecanonattrs"));
    }

    @Test
    void signatureAlgorithmIsEd25519() {
        CloudEvent signed = signEvent(buildTestEvent());
        assertEquals("ed25519", signed.getExtension("cesignaturealg").toString());
    }

    @Test
    void keyIdMatchesConfig() {
        CloudEvent signed = signEvent(buildTestEvent());
        assertEquals(KEY_ID, signed.getExtension("cekeyid").toString());
    }

    @Test
    void cecanonattrsContainsOnlyPresentAttributes() {
        // Event without subject — cecanonattrs should not include "subject"
        CloudEvent event =
                CloudEventBuilder.v1()
                        .withId("1")
                        .withSource(URI.create("/bu-alice"))
                        .withType("order.created")
                        .withDataContentType("application/json")
                        .withData("application/json", "{}".getBytes(StandardCharsets.UTF_8))
                        .build();

        CloudEvent signed = signEvent(event);
        String canonAttrs = signed.getExtension("cecanonattrs").toString();

        assertTrue(canonAttrs.contains("type"));
        assertTrue(canonAttrs.contains("source"));
        assertTrue(canonAttrs.contains("datacontenttype"));
        assertFalse(
                canonAttrs.contains("subject"), "Absent 'subject' should not be in cecanonattrs");
    }

    @Test
    void cecanonattrsIsSortedCommaSeparated() {
        CloudEvent signed = signEvent(buildTestEvent());
        String canonAttrs = signed.getExtension("cecanonattrs").toString();

        List<String> attrs = Arrays.asList(canonAttrs.split(","));
        List<String> sorted = attrs.stream().sorted().toList();
        assertEquals(sorted, attrs, "cecanonattrs should be sorted");
    }

    @Test
    void signatureIsValidBase64Url() {
        CloudEvent signed = signEvent(buildTestEvent());
        String sig = signed.getExtension("cesignature").toString();

        // Should not contain +, /, or = (base64url without padding)
        assertFalse(sig.contains("+"));
        assertFalse(sig.contains("/"));
        assertFalse(sig.contains("="));

        byte[] decoded = Base64.getUrlDecoder().decode(sig);
        assertEquals(64, decoded.length, "Ed25519 signature should be 64 bytes");
    }

    @Test
    void signatureVerifiesWithPublicKey() {
        CloudEvent event = buildTestEvent();
        CloudEvent signed = signEvent(event);

        // Rebuild canonical form using cecanonattrs (as the verifier would)
        String canonAttrs = signed.getExtension("cecanonattrs").toString();
        List<String> attrs = Arrays.asList(canonAttrs.split(","));
        byte[] canonical = CanonicalForm.build(signed, attrs);

        byte[] sigBytes =
                Base64.getUrlDecoder().decode(signed.getExtension("cesignature").toString());
        assertTrue(EventVerifier.verify(canonical, sigBytes, publicKey));
    }

    @Test
    void signedEventPreservesOriginalAttributes() {
        CloudEvent event = buildTestEvent();
        CloudEvent signed = signEvent(event);

        assertEquals(event.getId(), signed.getId());
        assertEquals(event.getSource(), signed.getSource());
        assertEquals(event.getType(), signed.getType());
        assertEquals(event.getSubject(), signed.getSubject());
        assertEquals(event.getDataContentType(), signed.getDataContentType());
        assertArrayEquals(event.getData().toBytes(), signed.getData().toBytes());
    }

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

    /** Simulates the signing handler logic without CDI. */
    private CloudEvent signEvent(CloudEvent event) {
        byte[] canonical = CanonicalForm.build(event, CANONICAL_ATTRS);
        String signature = signer.signToBase64Url(canonical);
        String presentAttrs = CanonicalForm.presentAttributes(event, CANONICAL_ATTRS);

        return CloudEventBuilder.from(Objects.requireNonNull(event))
                .withExtension("cesignature", Objects.requireNonNull(signature))
                .withExtension("cesignaturealg", "ed25519")
                .withExtension("cekeyid", KEY_ID)
                .withExtension("cecanonattrs", Objects.requireNonNull(presentAttrs))
                .build();
    }
}
