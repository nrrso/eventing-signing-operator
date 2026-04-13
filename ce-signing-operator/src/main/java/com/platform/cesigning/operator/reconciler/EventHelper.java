// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class EventHelper {

    private static final String REPORTING_COMPONENT = "ce-signing-operator";

    private EventHelper() {
    }

    public static Event normalEvent(HasMetadata resource, String reason, String message) {
        return buildEvent(resource, "Normal", reason, message);
    }

    public static Event warningEvent(HasMetadata resource, String reason, String message) {
        return buildEvent(resource, "Warning", reason, message);
    }

    private static Event buildEvent(HasMetadata resource, String type, String reason,
                                    String message) {
        String now = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        // Append a short hex suffix so each occurrence creates a distinct event object
        // rather than overwriting a previous one and resetting the count to 1.
        String suffix = Long.toHexString(System.nanoTime() & 0xFFFFFFL);
        String eventName = resource.getMetadata().getName() + "." + reason.toLowerCase() + "." + suffix;

        return new EventBuilder()
                .withNewMetadata()
                    .withName(eventName)
                    .withNamespace(resource.getMetadata().getNamespace())
                .endMetadata()
                .withType(type)
                .withReason(reason)
                .withMessage(message)
                .withNewInvolvedObject()
                    .withApiVersion(resource.getApiVersion())
                    .withKind(resource.getKind())
                    .withName(resource.getMetadata().getName())
                    .withNamespace(resource.getMetadata().getNamespace())
                    .withUid(resource.getMetadata().getUid())
                .endInvolvedObject()
                .withReportingComponent(REPORTING_COMPONENT)
                .withFirstTimestamp(now)
                .withLastTimestamp(now)
                .build();
    }
}
