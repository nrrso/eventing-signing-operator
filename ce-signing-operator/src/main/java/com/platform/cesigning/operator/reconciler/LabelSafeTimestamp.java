// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Encodes/decodes timestamps as epoch-seconds strings for use in Kubernetes labels.
 * K8s labels only allow [a-zA-Z0-9._-], so ISO 8601 timestamps (with colons) are invalid.
 */
public final class LabelSafeTimestamp {

    private LabelSafeTimestamp() {}

    /** Encode an OffsetDateTime as epoch seconds string (label-safe). */
    public static String encode(OffsetDateTime dateTime) {
        return String.valueOf(dateTime.toEpochSecond());
    }

    /** Decode an epoch-seconds label value back to OffsetDateTime (UTC). */
    public static OffsetDateTime decode(String labelValue) {
        return OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(Long.parseLong(labelValue)),
                ZoneOffset.UTC);
    }
}
