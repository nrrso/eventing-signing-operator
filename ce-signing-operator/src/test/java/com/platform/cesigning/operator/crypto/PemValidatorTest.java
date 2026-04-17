// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crypto;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PemValidatorTest {

    @Test
    void validEd25519PemPasses() throws IOException {
        KeyPairGenerator.GeneratedKeyPair kp = KeyPairGenerator.generate();
        PemValidator.Result result = PemValidator.validatePublicKeyPem(kp.publicPem());
        assertTrue(result.valid());
        assertNull(result.error());
    }

    @Test
    void missingPemHeadersFails() {
        String rawBase64 = "MCowBQYDK2VwAyEA0V75vQP7ll75xIggyEoUVFCpMh2GdrnlOakYyKilto4=";
        PemValidator.Result result = PemValidator.validatePublicKeyPem(rawBase64);
        assertFalse(result.valid());
        assertNotNull(result.error());
    }

    @Test
    void corruptBase64Fails() {
        String corruptPem =
                """
                -----BEGIN PUBLIC KEY-----
                !!!not-valid-base64!!!
                -----END PUBLIC KEY-----
                """;
        PemValidator.Result result = PemValidator.validatePublicKeyPem(corruptPem);
        assertFalse(result.valid());
        assertNotNull(result.error());
    }

    @Test
    void rsaKeyFails() {
        // A 2048-bit RSA public key — valid PEM but not Ed25519
        String rsaPem =
                """
                -----BEGIN PUBLIC KEY-----
                MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0Z3VS5JJcds3xfn/ygWe
                FIkj6iOE0NJQM4WKYZ26MhTNP9JMDWCHGZbJFYQC9kWMPILG/GnpGLuGTlWKpBQ
                oCdS/0nL+4YmqBzfBKMt/VerGMhRBIxadJEB7xPc2JWMtoF39zBnXmrrbCJQTqIS
                EHiUbqdS7J1mbGEfITILrOdp5K6mOj5LzsSsOMw1n4ZFoHw7zfxEwbcONFP9IHOE
                tR4VjYPobLRVNu8Sw+y/t6H0jfMsCIqYOJhMiI1KKFvALF4oqN2VF6f6BxGf9Fcx
                HBaLbMlALJnPCp+JlYb/BMaIqj9+ToVMnQYCPRfbyNBFKCaahWTl0ZV0TTy+Q0A0
                HwIDAQAB
                -----END PUBLIC KEY-----
                """;
        PemValidator.Result result = PemValidator.validatePublicKeyPem(rsaPem);
        assertFalse(result.valid());
        assertNotNull(result.error());
    }

    @Test
    void nullPemFails() {
        PemValidator.Result result = PemValidator.validatePublicKeyPem(null);
        assertFalse(result.valid());
        assertEquals("PEM string is null or blank", result.error());
    }

    @Test
    void blankPemFails() {
        PemValidator.Result result = PemValidator.validatePublicKeyPem("   ");
        assertFalse(result.valid());
        assertEquals("PEM string is null or blank", result.error());
    }
}
