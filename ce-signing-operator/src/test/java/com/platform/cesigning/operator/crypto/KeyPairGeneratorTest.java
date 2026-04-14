// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.openssl.PEMParser;
import org.junit.jupiter.api.Test;

class KeyPairGeneratorTest {

    @Test
    void generateProducesValidKeyPair() throws IOException {
        KeyPairGenerator.GeneratedKeyPair keyPair = KeyPairGenerator.generate();

        assertNotNull(keyPair.privatePem());
        assertNotNull(keyPair.publicPem());
        assertTrue(keyPair.privatePem().contains("PRIVATE KEY"));
        assertTrue(keyPair.publicPem().contains("PUBLIC KEY"));
    }

    @Test
    void generatedKeysCanSignAndVerify() throws IOException {
        KeyPairGenerator.GeneratedKeyPair keyPair = KeyPairGenerator.generate();

        // Parse private key from PEM
        org.bouncycastle.asn1.pkcs.PrivateKeyInfo pkInfo;
        try (PEMParser parser = new PEMParser(new StringReader(keyPair.privatePem()))) {
            pkInfo = (org.bouncycastle.asn1.pkcs.PrivateKeyInfo) parser.readObject();
        }
        org.bouncycastle.asn1.ASN1OctetString octetString =
                org.bouncycastle.asn1.ASN1OctetString.getInstance(pkInfo.parsePrivateKey());
        Ed25519PrivateKeyParameters privateKey =
                new Ed25519PrivateKeyParameters(octetString.getOctets(), 0);

        // Parse public key from PEM
        SubjectPublicKeyInfo spki;
        try (PEMParser parser = new PEMParser(new StringReader(keyPair.publicPem()))) {
            spki = (SubjectPublicKeyInfo) parser.readObject();
        }
        Ed25519PublicKeyParameters publicKey =
                new Ed25519PublicKeyParameters(spki.getPublicKeyData().getBytes(), 0);

        // Sign
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(data, 0, data.length);
        byte[] signature = signer.generateSignature();

        assertEquals(64, signature.length);

        // Verify
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(data, 0, data.length);
        assertTrue(verifier.verifySignature(signature));
    }

    @Test
    void eachGenerationProducesDifferentKeys() throws IOException {
        KeyPairGenerator.GeneratedKeyPair kp1 = KeyPairGenerator.generate();
        KeyPairGenerator.GeneratedKeyPair kp2 = KeyPairGenerator.generate();

        assertNotEquals(kp1.privatePem(), kp2.privatePem());
        assertNotEquals(kp1.publicPem(), kp2.publicPem());
    }
}
