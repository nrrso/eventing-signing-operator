// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProducerPolicyReconcilerTest {

    @Test
    void prepareEventSourcesOverrideExists() throws NoSuchMethodException {
        var method =
                ProducerPolicyReconciler.class.getDeclaredMethod(
                        "prepareEventSources", EventSourceContext.class);
        assertNotNull(method, "prepareEventSources should be overridden");
        assertEquals(List.class, method.getReturnType());
        assertEquals(
                ProducerPolicyReconciler.class,
                method.getDeclaringClass(),
                "prepareEventSources should be declared on ProducerPolicyReconciler, not inherited");
    }

    @Test
    void prepareEventSourcesParameterIsProducerPolicyContext() throws NoSuchMethodException {
        var method =
                ProducerPolicyReconciler.class.getDeclaredMethod(
                        "prepareEventSources", EventSourceContext.class);
        var paramTypes = method.getParameterTypes();
        assertEquals(1, paramTypes.length);
        assertEquals(EventSourceContext.class, paramTypes[0]);
    }

    @Test
    void reconcilerOverridesCleanup() throws NoSuchMethodException {
        // Verify cleanup still exists (deletion of registry entries on ProducerPolicy delete)
        var method =
                ProducerPolicyReconciler.class.getDeclaredMethod(
                        "cleanup",
                        CloudEventSigningProducerPolicy.class,
                        io.javaoperatorsdk.operator.api.reconciler.Context.class);
        assertNotNull(method, "cleanup should be overridden for registry entry removal");
    }
}
