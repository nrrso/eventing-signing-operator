// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crypto;

import java.io.IOException;
import java.io.StringWriter;
import java.security.SecureRandom;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;

/** Generates Ed25519 keypairs via BouncyCastle and exports them as PEM strings. */
public class KeyPairGenerator {

    private KeyPairGenerator() {}

    public static GeneratedKeyPair generate() throws IOException {
        Ed25519KeyPairGenerator keyGen = new Ed25519KeyPairGenerator();
        keyGen.init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        AsymmetricCipherKeyPair keyPair = keyGen.generateKeyPair();

        Ed25519PrivateKeyParameters privateKey = (Ed25519PrivateKeyParameters) keyPair.getPrivate();
        Ed25519PublicKeyParameters publicKey = (Ed25519PublicKeyParameters) keyPair.getPublic();

        String privatePem = toPem(PrivateKeyInfoFactory.createPrivateKeyInfo(privateKey));
        String publicPem = toPem(SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKey));

        return new GeneratedKeyPair(privatePem, publicPem);
    }

    private static String toPem(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(obj);
        }
        return sw.toString();
    }

    public record GeneratedKeyPair(String privatePem, String publicPem) {}
}
