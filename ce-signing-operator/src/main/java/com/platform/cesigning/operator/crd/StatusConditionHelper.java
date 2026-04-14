// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class StatusConditionHelper {

    private StatusConditionHelper() {}

    /**
     * Sets a condition in the list, returning true if the condition actually changed (status
     * transition, reason/message change, or first addition).
     */
    public static boolean setCondition(
            List<Condition> conditions, String type, String status, String reason, String message) {
        String now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        for (int i = 0; i < conditions.size(); i++) {
            Condition existing = conditions.get(i);
            if (type.equals(existing.getType())) {
                if (!status.equals(existing.getStatus())) {
                    conditions.set(
                            i,
                            new ConditionBuilder()
                                    .withType(type)
                                    .withStatus(status)
                                    .withReason(reason)
                                    .withMessage(message)
                                    .withLastTransitionTime(now)
                                    .build());
                    return true;
                }
                boolean reasonChanged = !reason.equals(existing.getReason());
                boolean messageChanged = !message.equals(existing.getMessage());
                if (reasonChanged || messageChanged) {
                    conditions.set(
                            i,
                            new ConditionBuilder()
                                    .withType(type)
                                    .withStatus(status)
                                    .withReason(reason)
                                    .withMessage(message)
                                    .withLastTransitionTime(existing.getLastTransitionTime())
                                    .build());
                    return true;
                }
                return false;
            }
        }
        conditions.add(
                new ConditionBuilder()
                        .withType(type)
                        .withStatus(status)
                        .withReason(reason)
                        .withMessage(message)
                        .withLastTransitionTime(now)
                        .build());
        return true;
    }
}
