// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.crypto;

import io.cloudevents.CloudEvent;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds a deterministic byte representation of a CloudEvent for signing/verification.
 * Operates on the parsed CloudEvent SDK object, never on raw wire bytes.
 */
public final class CanonicalForm {

    private CanonicalForm() {
    }

    /**
     * Build canonical bytes from a CloudEvent and a list of attribute names to include.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Filter: keep only attributes present on the event</li>
     *   <li>Sort remaining names lexicographically (UTF-8 byte order)</li>
     *   <li>For each attribute: append name=value\n using CloudEvents string representation</li>
     *   <li>Append data= followed by canonicalized data bytes</li>
     * </ol>
     */
    public static byte[] build(CloudEvent event, List<String> canonicalAttributes) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(canonicalAttributes, "canonicalAttributes must not be null");

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // Collect present attributes in sorted order (TreeSet gives lexicographic UTF-8 sort)
            TreeSet<String> sorted = new TreeSet<>();
            for (String attr : canonicalAttributes) {
                Object value = getAttributeValue(event, attr);
                if (value != null) {
                    sorted.add(attr);
                }
            }

            // Write sorted attribute lines
            for (String attr : sorted) {
                Object value = getAttributeValue(event, attr);
                String strValue = toCloudEventsString(value);
                out.write(attr.getBytes(StandardCharsets.UTF_8));
                out.write('=');
                out.write(strValue.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }

            // Write data
            out.write("data=".getBytes(StandardCharsets.UTF_8));
            if (event.getData() != null) {
                byte[] dataBytes = event.getData().toBytes();
                if (isJsonContentType(event.getDataContentType())) {
                    // Always parse and re-canonicalize JSON via JCS
                    String jsonStr = new String(dataBytes, StandardCharsets.UTF_8);
                    JsonCanonicalizer canonicalizer = new JsonCanonicalizer(jsonStr);
                    out.write(canonicalizer.getEncodedUTF8());
                } else {
                    // Binary data: use raw bytes
                    out.write(dataBytes);
                }
            }
            // No data: "data=" with empty value

            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build canonical form", e);
        }
    }

    /**
     * Returns the sorted, comma-separated list of canonical attributes actually present on the event.
     */
    public static String presentAttributes(CloudEvent event, List<String> canonicalAttributes) {
        TreeSet<String> sorted = new TreeSet<>();
        for (String attr : canonicalAttributes) {
            Object value = getAttributeValue(event, attr);
            if (value != null) {
                sorted.add(attr);
            }
        }
        return String.join(",", sorted);
    }

    private static Object getAttributeValue(CloudEvent event, String name) {
        return switch (name) {
            case "id" -> event.getId();
            case "source" -> event.getSource();
            case "type" -> event.getType();
            case "specversion" -> event.getSpecVersion();
            case "datacontenttype" -> event.getDataContentType();
            case "dataschema" -> event.getDataSchema();
            case "subject" -> event.getSubject();
            case "time" -> event.getTime();
            default -> event.getExtension(name);
        };
    }

    private static String toCloudEventsString(Object value) {
        if (value == null) {
            return "";
        }
        // CloudEvents SDK types: String, URI, OffsetDateTime, Integer, Boolean
        // All have sensible toString() that matches the CloudEvents spec string representation
        return value.toString();
    }

    static boolean isJsonContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        // Matches */json and */*+json
        String lower = contentType.toLowerCase();
        int semicolon = lower.indexOf(';');
        if (semicolon >= 0) {
            lower = lower.substring(0, semicolon).trim();
        }
        return lower.endsWith("/json") || lower.endsWith("+json");
    }
}
