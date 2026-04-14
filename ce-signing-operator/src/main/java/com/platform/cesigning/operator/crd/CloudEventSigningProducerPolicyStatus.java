// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

public class CloudEventSigningProducerPolicyStatus {

    private List<Condition> conditions = new ArrayList<>();
    private String keyId;
    private String keyCreated;
    private String keyExpiresAt;
    private List<ProducerStatus> producers = new ArrayList<>();
    private String lastReconciled;
    private Long observedGeneration;

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getKeyCreated() {
        return keyCreated;
    }

    public void setKeyCreated(String keyCreated) {
        this.keyCreated = keyCreated;
    }

    public String getKeyExpiresAt() {
        return keyExpiresAt;
    }

    public void setKeyExpiresAt(String keyExpiresAt) {
        this.keyExpiresAt = keyExpiresAt;
    }

    public List<ProducerStatus> getProducers() {
        return producers;
    }

    public void setProducers(List<ProducerStatus> producers) {
        this.producers = producers;
    }

    public String getLastReconciled() {
        return lastReconciled;
    }

    public void setLastReconciled(String lastReconciled) {
        this.lastReconciled = lastReconciled;
    }

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public boolean setCondition(String type, String status, String reason, String message) {
        return StatusConditionHelper.setCondition(conditions, type, status, reason, message);
    }

    public static class ProducerStatus {
        private String name;
        private String signingEndpoint;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getSigningEndpoint() {
            return signingEndpoint;
        }

        public void setSigningEndpoint(String signingEndpoint) {
            this.signingEndpoint = signingEndpoint;
        }
    }
}
