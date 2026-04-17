// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crypto;

import java.io.StringReader;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.openssl.PEMParser;

/** Validates PEM-encoded Ed25519 public keys using BouncyCastle. */
public class PemValidator {

    private PemValidator() {}

    /** Validates that the given PEM string is a well-formed Ed25519 public key. */
    public static Result validatePublicKeyPem(String pem) {
        if (pem == null || pem.isBlank()) {
            return Result.fail("PEM string is null or blank");
        }
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            if (!(obj instanceof SubjectPublicKeyInfo spki)) {
                String type = obj == null ? "null" : obj.getClass().getName();
                return Result.fail("Unsupported PEM object: " + type);
            }
            byte[] rawKey = spki.getPublicKeyData().getBytes();
            new Ed25519PublicKeyParameters(rawKey, 0);
            return Result.ok();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    public record Result(boolean valid, String error) {
        public static Result ok() {
            return new Result(true, null);
        }

        public static Result fail(String error) {
            return new Result(false, error);
        }
    }
}
