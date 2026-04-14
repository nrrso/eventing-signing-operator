// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import java.time.OffsetDateTime;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

public record PublicKeyEntry(
        String namespace,
        String keyId,
        Ed25519PublicKeyParameters publicKey,
        String algorithm,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt,
        String status) {
    public boolean isUsableForVerification() {
        return "active".equals(status) || "rotating".equals(status);
    }
}
