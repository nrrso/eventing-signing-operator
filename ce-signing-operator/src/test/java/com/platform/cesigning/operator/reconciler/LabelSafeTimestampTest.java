// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class LabelSafeTimestampTest {

    @Test
    void encodeProducesDigitsOnly() {
        OffsetDateTime dt = OffsetDateTime.of(2026, 4, 9, 11, 9, 21, 0, ZoneOffset.UTC);
        String encoded = LabelSafeTimestamp.encode(dt);
        assertTrue(encoded.matches("\\d+"), "Encoded value must be digits only, got: " + encoded);
    }

    @Test
    void roundtripPreservesTimestampToSecondPrecision() {
        OffsetDateTime original = OffsetDateTime.of(2026, 4, 9, 11, 9, 21, 0, ZoneOffset.UTC);
        String encoded = LabelSafeTimestamp.encode(original);
        OffsetDateTime decoded = LabelSafeTimestamp.decode(encoded);
        assertEquals(original, decoded);
    }

    @Test
    void decodeKnownEpochValue() {
        // 2025-01-01T00:00:00Z = 1735689600
        OffsetDateTime decoded = LabelSafeTimestamp.decode("1735689600");
        assertEquals(2025, decoded.getYear());
        assertEquals(1, decoded.getMonthValue());
        assertEquals(1, decoded.getDayOfMonth());
        assertEquals(0, decoded.getHour());
    }

    @Test
    void encodedValueIsValidKubernetesLabel() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String encoded = LabelSafeTimestamp.encode(now);
        // K8s label regex: (([A-Za-z0-9][-A-Za-z0-9_.]*)?[A-Za-z0-9])?
        assertTrue(encoded.matches("[A-Za-z0-9][-A-Za-z0-9_.]*[A-Za-z0-9]|[A-Za-z0-9]"),
                "Encoded value must be valid K8s label: " + encoded);
    }
}
