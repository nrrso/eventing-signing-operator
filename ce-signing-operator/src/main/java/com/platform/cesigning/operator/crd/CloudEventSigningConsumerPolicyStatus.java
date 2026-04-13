// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;

import java.util.ArrayList;
import java.util.List;

public class CloudEventSigningConsumerPolicyStatus {

    private List<Condition> conditions = new ArrayList<>();
    private List<ConsumerTriggerStatus> consumers = new ArrayList<>();
    private String lastReconciled;
    private Long observedGeneration;

    public List<Condition> getConditions() { return conditions; }
    public void setConditions(List<Condition> conditions) { this.conditions = conditions; }

    public List<ConsumerTriggerStatus> getConsumers() { return consumers; }
    public void setConsumers(List<ConsumerTriggerStatus> consumers) { this.consumers = consumers; }

    public String getLastReconciled() { return lastReconciled; }
    public void setLastReconciled(String lastReconciled) { this.lastReconciled = lastReconciled; }

    public Long getObservedGeneration() { return observedGeneration; }
    public void setObservedGeneration(Long observedGeneration) { this.observedGeneration = observedGeneration; }

    public boolean setCondition(String type, String status, String reason, String message) {
        return StatusConditionHelper.setCondition(conditions, type, status, reason, message);
    }

    public static class ConsumerTriggerStatus {
        private String name;
        private boolean triggersReady;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isTriggersReady() { return triggersReady; }
        public void setTriggersReady(boolean triggersReady) { this.triggersReady = triggersReady; }
    }
}
