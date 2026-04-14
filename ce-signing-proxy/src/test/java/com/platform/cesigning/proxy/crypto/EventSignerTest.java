// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EventSignerTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;

    @BeforeAll
    static void setUp() throws IOException {
        Path privateKeyPath = Path.of("src/test/resources/test-private.pem");
        Path publicKeyPath = Path.of("src/test/resources/test-public.pem");
        signer = new EventSigner(privateKeyPath);
        publicKey = EventVerifier.loadPublicKey(publicKeyPath);
    }

    @Test
    void signProduces64ByteSignature() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);
        assertEquals(64, signature.length);
    }

    @Test
    void signatureVerifiesWithCorrectPublicKey() {
        byte[] data = "canonical form bytes".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);
        assertTrue(EventVerifier.verify(data, signature, publicKey));
    }

    @Test
    void signToBase64UrlReturnsValidEncoding() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        String base64url = signer.signToBase64Url(data);

        // Base64url should not contain +, /, or =
        assertFalse(base64url.contains("+"));
        assertFalse(base64url.contains("/"));
        assertFalse(base64url.contains("="));

        // Should decode to 64 bytes
        byte[] decoded = Base64.getUrlDecoder().decode(base64url);
        assertEquals(64, decoded.length);
    }

    @Test
    void differentInputsProduceDifferentSignatures() {
        byte[] data1 = "input one".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "input two".getBytes(StandardCharsets.UTF_8);
        byte[] sig1 = signer.sign(data1);
        byte[] sig2 = signer.sign(data2);
        assertFalse(java.util.Arrays.equals(sig1, sig2));
    }

    @Test
    void sameInputProducesSameSignature() {
        byte[] data = "deterministic input".getBytes(StandardCharsets.UTF_8);
        byte[] sig1 = signer.sign(data);
        byte[] sig2 = signer.sign(data);
        assertArrayEquals(sig1, sig2, "Ed25519 is deterministic");
    }
}
