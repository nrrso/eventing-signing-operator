// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.ProxyConfig;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

public class HpaEnabledCondition
        implements Condition<HorizontalPodAutoscaler, CloudEventSigningProducerPolicy> {

    @Override
    public boolean isMet(DependentResource<HorizontalPodAutoscaler, CloudEventSigningProducerPolicy> dependentResource,
                         CloudEventSigningProducerPolicy primary,
                         Context<CloudEventSigningProducerPolicy> context) {
        ProxyConfig.HpaConfig hpa = primary.getSpec().getProxy().getHpa();
        return hpa != null && hpa.isEnabled();
    }
}
