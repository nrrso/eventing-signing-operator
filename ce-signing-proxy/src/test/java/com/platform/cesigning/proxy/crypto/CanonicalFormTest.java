// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalFormTest {

    @Test
    void standardAttributesSortedLexicographically() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/bu-alice"))
                .withType("order.created")
                .withSubject("order-123")
                .withDataContentType("application/json")
                .withData("application/json", "{\"amount\":42}".getBytes(StandardCharsets.UTF_8))
                .build();

        byte[] canonical = CanonicalForm.build(event, List.of("type", "source", "subject", "datacontenttype"));
        String result = new String(canonical, StandardCharsets.UTF_8);

        // Attributes must be sorted: datacontenttype, source, subject, type
        assertTrue(result.startsWith("datacontenttype=application/json\n"));
        assertTrue(result.contains("source=/bu-alice\n"));
        assertTrue(result.contains("subject=order-123\n"));
        assertTrue(result.contains("type=order.created\n"));

        // Verify order: datacontenttype < source < subject < type
        int srcPos = result.indexOf("\nsource=");
        int subPos = result.indexOf("\nsubject=");
        int typPos = result.indexOf("\ntype=");
        // datacontenttype is first line so check it via startsWith (already done)
        assertTrue(srcPos < subPos);
        assertTrue(subPos < typPos);

        // data= with JCS-canonicalized JSON
        assertTrue(result.contains("data={\"amount\":42}"));
    }

    @Test
    void absentAttributesSkipped() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/bu-alice"))
                .withType("order.created")
                .withData("application/json", "{}".getBytes(StandardCharsets.UTF_8))
                .build();

        // subject and datacontenttype are absent (datacontenttype not set explicitly without data content type)
        byte[] canonical = CanonicalForm.build(event, List.of("type", "source", "subject", "datacontenttype"));
        String result = new String(canonical, StandardCharsets.UTF_8);

        // subject is absent, should not appear
        assertFalse(result.contains("subject="));
        assertTrue(result.contains("source=/bu-alice\n"));
        assertTrue(result.contains("type=order.created\n"));
    }

    @Test
    void jsonJcsCanonicalizes() {
        // Two events with same data but different key ordering
        CloudEvent event1 = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{\"b\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8))
                .build();

        CloudEvent event2 = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{\"a\":2,\"b\":1}".getBytes(StandardCharsets.UTF_8))
                .build();

        byte[] canonical1 = CanonicalForm.build(event1, List.of("type", "source"));
        byte[] canonical2 = CanonicalForm.build(event2, List.of("type", "source"));

        assertArrayEquals(canonical1, canonical2, "Different JSON key orderings must produce identical canonical bytes");
    }

    @Test
    void jsonWithWhitespaceNormalized() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{ \"z\" : 1 , \"a\" : 2 }".getBytes(StandardCharsets.UTF_8))
                .build();

        byte[] canonical = CanonicalForm.build(event, List.of("type", "source"));
        String result = new String(canonical, StandardCharsets.UTF_8);

        assertTrue(result.endsWith("data={\"a\":2,\"z\":1}"));
    }

    @Test
    void binaryDataPassedThrough() {
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/octet-stream")
                .withData("application/octet-stream", binaryData)
                .build();

        byte[] canonical = CanonicalForm.build(event, List.of("type", "source", "datacontenttype"));
        String prefix = new String(canonical, 0, canonical.length - binaryData.length, StandardCharsets.UTF_8);
        assertTrue(prefix.endsWith("data="));

        // Last 4 bytes should be the raw binary data
        byte[] dataPart = new byte[binaryData.length];
        System.arraycopy(canonical, canonical.length - binaryData.length, dataPart, 0, binaryData.length);
        assertArrayEquals(binaryData, dataPart);
    }

    @Test
    void emptyDataProducesDataEqualsOnly() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .build();

        byte[] canonical = CanonicalForm.build(event, List.of("type", "source"));
        String result = new String(canonical, StandardCharsets.UTF_8);

        assertTrue(result.endsWith("data="), "No data should produce 'data=' with empty value");
    }

    @Test
    void timestampAttribute() {
        OffsetDateTime time = OffsetDateTime.parse("2024-01-15T10:30:00Z");
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withTime(time)
                .build();

        byte[] canonical = CanonicalForm.build(event, List.of("type", "time"));
        String result = new String(canonical, StandardCharsets.UTF_8);

        // OffsetDateTime.toString() may drop trailing :00 seconds
        assertTrue(result.contains("time=2024-01-15T10:30Z\n") || result.contains("time=2024-01-15T10:30:00Z\n"));
    }

    @Test
    void unicodeInJsonData() {
        String json = "{\"name\":\"caf\\u00e9\",\"emoji\":\"\\ud83d\\ude00\"}";
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", json.getBytes(StandardCharsets.UTF_8))
                .build();

        // Should not throw and should produce deterministic output
        byte[] canonical = CanonicalForm.build(event, List.of("type"));
        assertNotNull(canonical);
        assertTrue(canonical.length > 0);
    }

    @Test
    void presentAttributesReturnsSortedCommaList() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/bu-alice"))
                .withType("order.created")
                .build();

        String present = CanonicalForm.presentAttributes(event, List.of("type", "source", "subject", "datacontenttype"));
        assertEquals("source,type", present);
    }

    @Test
    void isJsonContentTypeVariants() {
        assertTrue(CanonicalForm.isJsonContentType("application/json"));
        assertTrue(CanonicalForm.isJsonContentType("application/cloudevents+json"));
        assertTrue(CanonicalForm.isJsonContentType("text/json"));
        assertTrue(CanonicalForm.isJsonContentType("application/json; charset=utf-8"));
        assertFalse(CanonicalForm.isJsonContentType("application/octet-stream"));
        assertFalse(CanonicalForm.isJsonContentType("text/plain"));
        assertFalse(CanonicalForm.isJsonContentType(null));
    }

    @Test
    void deterministicAcrossMultipleCalls() {
        CloudEvent event = CloudEventBuilder.v1()
                .withId("1")
                .withSource(URI.create("/test"))
                .withType("test")
                .withDataContentType("application/json")
                .withData("application/json", "{\"key\":\"value\"}".getBytes(StandardCharsets.UTF_8))
                .build();

        List<String> attrs = List.of("type", "source", "datacontenttype");
        byte[] first = CanonicalForm.build(event, attrs);
        byte[] second = CanonicalForm.build(event, attrs);
        assertArrayEquals(first, second);
    }
}
