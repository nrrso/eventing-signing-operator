// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.openssl.PEMParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Signs canonical CloudEvent bytes using an Ed25519 private key.
 * Uses BouncyCastle directly (not JCA wrapper) for GraalVM compatibility.
 */
public class EventSigner {

    private final Ed25519PrivateKeyParameters privateKey;

    public EventSigner(Path privateKeyPath) throws IOException {
        this.privateKey = loadPrivateKey(privateKeyPath);
    }

    public EventSigner(Ed25519PrivateKeyParameters privateKey) {
        this.privateKey = privateKey;
    }

    /**
     * Sign the canonical form bytes.
     *
     * @return 64-byte Ed25519 signature
     */
    public byte[] sign(byte[] canonical) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(canonical, 0, canonical.length);
        return signer.generateSignature();
    }

    /**
     * Sign and return as base64url-encoded string (no padding).
     */
    public String signToBase64Url(byte[] canonical) {
        byte[] sig = sign(canonical);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    }

    private static Ed25519PrivateKeyParameters loadPrivateKey(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path);
             PEMParser parser = new PEMParser(reader)) {
            Object obj = parser.readObject();
            if (obj instanceof PrivateKeyInfo pkInfo) {
                ASN1OctetString octetString = ASN1OctetString.getInstance(pkInfo.parsePrivateKey());
                byte[] rawKey = octetString.getOctets();
                return new Ed25519PrivateKeyParameters(rawKey, 0);
            }
            throw new IOException("Unsupported PEM object: " + (obj == null ? "null" : obj.getClass().getName()));
        }
    }
}
