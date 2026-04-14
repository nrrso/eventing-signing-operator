// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

public class DeploymentReadyCondition
        implements Condition<Deployment, CloudEventSigningProducerPolicy> {

    @Override
    public boolean isMet(
            DependentResource<Deployment, CloudEventSigningProducerPolicy> dependentResource,
            CloudEventSigningProducerPolicy primary,
            Context<CloudEventSigningProducerPolicy> context) {
        return dependentResource
                .getSecondaryResource(primary, context)
                .map(
                        deployment -> {
                            DeploymentStatus status = deployment.getStatus();
                            return status != null
                                    && status.getAvailableReplicas() != null
                                    && status.getAvailableReplicas() >= 1;
                        })
                .orElse(false);
    }
}
