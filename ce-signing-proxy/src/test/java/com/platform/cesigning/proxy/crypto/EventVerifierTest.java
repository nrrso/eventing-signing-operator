// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class EventVerifierTest {

    private static EventSigner signer;
    private static Ed25519PublicKeyParameters publicKey;

    @BeforeAll
    static void setUp() throws IOException {
        signer = new EventSigner(Path.of("src/test/resources/test-private.pem"));
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    @Test
    void validSignatureVerifies() {
        byte[] data = "valid data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);
        assertTrue(EventVerifier.verify(data, signature, publicKey));
    }

    @Test
    void badSignatureRejected() {
        byte[] data = "some data".getBytes(StandardCharsets.UTF_8);
        byte[] badSignature = new byte[64]; // all zeros
        assertFalse(EventVerifier.verify(data, badSignature, publicKey));
    }

    @Test
    void wrongKeyRejected() {
        byte[] data = "some data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);

        // Generate a different keypair
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        var wrongKeyPair = keyGen.generateKeyPair();
        Ed25519PublicKeyParameters wrongPublicKey =
                (Ed25519PublicKeyParameters) wrongKeyPair.getPublic();

        assertFalse(EventVerifier.verify(data, signature, wrongPublicKey));
    }

    @Test
    void tamperedDataRejected() {
        byte[] data = "original data".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);

        byte[] tampered = "tampered data".getBytes(StandardCharsets.UTF_8);
        assertFalse(EventVerifier.verify(tampered, signature, publicKey));
    }

    @Test
    void singleByteMutationRejected() {
        byte[] data = "some canonical form".getBytes(StandardCharsets.UTF_8);
        byte[] signature = signer.sign(data);

        // Flip one bit in the data
        byte[] mutated = data.clone();
        mutated[0] ^= 0x01;
        assertFalse(EventVerifier.verify(mutated, signature, publicKey));
    }

    @Test
    void parsePublicKeyPemString() throws IOException {
        String pem =
                """
                -----BEGIN PUBLIC KEY-----
                MCowBQYDK2VwAyEAr60YG9I+F/p7ABER5TmSnaBQ6M/rf4vGi4YGrYEq2ds=
                -----END PUBLIC KEY-----
                """;
        Ed25519PublicKeyParameters key = EventVerifier.parsePublicKeyPem(pem);
        assertNotNull(key);

        // Verify a signature with this key
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] sig = signer.sign(data);
        assertTrue(EventVerifier.verify(data, sig, key));
    }
}
