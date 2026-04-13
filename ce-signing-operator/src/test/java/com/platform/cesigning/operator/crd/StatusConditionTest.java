// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.crd;

import io.fabric8.kubernetes.api.model.Condition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatusConditionTest {

    @Test
    void setConditionAddsNewCondition() {
        var status = new CloudEventSigningProducerPolicyStatus();
        status.setCondition("Ready", "True", "Reconciled", "All resources ready");

        assertEquals(1, status.getConditions().size());
        Condition c = status.getConditions().get(0);
        assertEquals("Ready", c.getType());
        assertEquals("True", c.getStatus());
        assertEquals("Reconciled", c.getReason());
        assertEquals("All resources ready", c.getMessage());
        assertNotNull(c.getLastTransitionTime());
    }

    @Test
    void setConditionUpdatesExistingConditionSameStatus() {
        var status = new CloudEventSigningProducerPolicyStatus();
        status.setCondition("Ready", "True", "Reconciled", "First message");
        String firstTransitionTime = status.getConditions().get(0).getLastTransitionTime();

        status.setCondition("Ready", "True", "Reconciled", "Updated message");

        assertEquals(1, status.getConditions().size());
        Condition c = status.getConditions().get(0);
        assertEquals("Updated message", c.getMessage());
        assertEquals(firstTransitionTime, c.getLastTransitionTime());
    }

    @Test
    void setConditionUpdatesTransitionTimeOnStatusFlip() {
        var status = new CloudEventSigningProducerPolicyStatus();
        status.setCondition("Ready", "True", "Reconciled", "OK");
        String firstTransitionTime = status.getConditions().get(0).getLastTransitionTime();

        status.setCondition("Ready", "False", "Failed", "Error occurred");

        assertEquals(1, status.getConditions().size());
        Condition c = status.getConditions().get(0);
        assertEquals("False", c.getStatus());
        assertEquals("Failed", c.getReason());
        assertNotEquals(firstTransitionTime, c.getLastTransitionTime());
    }

    @Test
    void multipleConditionTypesTrackedIndependently() {
        var status = new CloudEventSigningProducerPolicyStatus();
        status.setCondition("Ready", "False", "Pending", "Waiting");
        status.setCondition("KeyPairReady", "True", "KeyCreated", "Key generated");
        status.setCondition("SigningProxyReady", "False", "DeploymentNotReady", "No pods");

        assertEquals(3, status.getConditions().size());
        assertEquals("Ready", status.getConditions().get(0).getType());
        assertEquals("KeyPairReady", status.getConditions().get(1).getType());
        assertEquals("SigningProxyReady", status.getConditions().get(2).getType());
    }

    @Test
    void consumerStatusConditionsWorkIdentically() {
        var status = new CloudEventSigningConsumerPolicyStatus();
        status.setCondition("Ready", "True", "Reconciled", "OK");
        status.setCondition("VerifyingProxyReady", "True", "Available", "Pods running");

        assertEquals(2, status.getConditions().size());

        status.setCondition("Ready", "False", "Failed", "Error");
        assertEquals(2, status.getConditions().size());
        assertEquals("False", status.getConditions().get(0).getStatus());
    }
}
