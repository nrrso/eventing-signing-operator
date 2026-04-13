// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.openssl.PEMParser;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies Ed25519 signatures against canonical CloudEvent bytes.
 * Stateless — receives the public key as a parameter.
 */
public class EventVerifier {

    private EventVerifier() {
    }

    /**
     * Verify an Ed25519 signature against canonical bytes using the given public key.
     */
    public static boolean verify(byte[] canonical, byte[] signature, Ed25519PublicKeyParameters publicKey) {
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(canonical, 0, canonical.length);
        return verifier.verifySignature(signature);
    }

    /**
     * Load an Ed25519 public key from a PEM file.
     */
    public static Ed25519PublicKeyParameters loadPublicKey(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return parsePublicKeyPem(reader);
        }
    }

    /**
     * Parse an Ed25519 public key from a PEM string.
     */
    public static Ed25519PublicKeyParameters parsePublicKeyPem(String pem) throws IOException {
        try (Reader reader = new StringReader(pem)) {
            return parsePublicKeyPem(reader);
        }
    }

    private static Ed25519PublicKeyParameters parsePublicKeyPem(Reader reader) throws IOException {
        try (PEMParser parser = new PEMParser(reader)) {
            Object obj = parser.readObject();
            if (obj instanceof SubjectPublicKeyInfo spki) {
                byte[] rawKey = spki.getPublicKeyData().getBytes();
                return new Ed25519PublicKeyParameters(rawKey, 0);
            }
            throw new IOException("Unsupported PEM object: " + (obj == null ? "null" : obj.getClass().getName()));
        }
    }
}
