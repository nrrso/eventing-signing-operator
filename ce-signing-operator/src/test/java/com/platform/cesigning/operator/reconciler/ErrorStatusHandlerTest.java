// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicyStatus;
import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicyStatus;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStatusHandlerTest {

    @Test
    void producerReconcilerImplementsReconciler() {
        assertTrue(Reconciler.class.isAssignableFrom(ProducerPolicyReconciler.class),
                "ProducerPolicyReconciler must implement Reconciler (which includes updateErrorStatus)");
    }

    @Test
    void consumerReconcilerImplementsReconciler() {
        assertTrue(Reconciler.class.isAssignableFrom(ConsumerPolicyReconciler.class),
                "ConsumerPolicyReconciler must implement Reconciler (which includes updateErrorStatus)");
    }

    @Test
    void producerUpdateErrorStatusSetsReadyFalse() {
        var reconciler = new ProducerPolicyReconciler();
        var resource = new CloudEventSigningProducerPolicy();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("test-policy")
                .withNamespace("test-ns")
                .build());
        resource.setStatus(new CloudEventSigningProducerPolicyStatus());

        // Call updateErrorStatus directly — it should set conditions without needing context.getClient()
        // We pass null context since the event emission is wrapped in try-catch
        var result = reconciler.updateErrorStatus(resource, null, new RuntimeException("API server timeout"));

        assertNotNull(result);
        var status = resource.getStatus();
        assertNotNull(status.getConditions());
        assertEquals(1, status.getConditions().size());

        Condition c = status.getConditions().get(0);
        assertEquals("Ready", c.getType());
        assertEquals("False", c.getStatus());
        assertEquals("ReconcileFailed", c.getReason());
        assertEquals("API server timeout", c.getMessage());
    }

    @Test
    void consumerUpdateErrorStatusSetsReadyFalse() {
        var reconciler = new ConsumerPolicyReconciler();
        var resource = new CloudEventSigningConsumerPolicy();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("test-policy")
                .withNamespace("test-ns")
                .build());
        resource.setStatus(new CloudEventSigningConsumerPolicyStatus());

        var result = reconciler.updateErrorStatus(resource, null, new RuntimeException("Connection refused"));

        assertNotNull(result);
        var status = resource.getStatus();
        assertNotNull(status.getConditions());
        assertEquals(1, status.getConditions().size());

        Condition c = status.getConditions().get(0);
        assertEquals("Ready", c.getType());
        assertEquals("False", c.getStatus());
        assertEquals("ReconcileFailed", c.getReason());
        assertEquals("Connection refused", c.getMessage());
    }

    @Test
    void producerUpdateErrorStatusInitializesNullStatus() {
        var reconciler = new ProducerPolicyReconciler();
        var resource = new CloudEventSigningProducerPolicy();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("test-policy")
                .withNamespace("test-ns")
                .build());
        // Status is null initially

        reconciler.updateErrorStatus(resource, null, new RuntimeException("error"));

        assertNotNull(resource.getStatus());
        assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    }

    @Test
    void consumerUpdateErrorStatusInitializesNullStatus() {
        var reconciler = new ConsumerPolicyReconciler();
        var resource = new CloudEventSigningConsumerPolicy();
        resource.setMetadata(new ObjectMetaBuilder()
                .withName("test-policy")
                .withNamespace("test-ns")
                .build());

        reconciler.updateErrorStatus(resource, null, new RuntimeException("error"));

        assertNotNull(resource.getStatus());
        assertEquals("False", resource.getStatus().getConditions().get(0).getStatus());
    }
}
