// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.consumer;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.workflow.Condition;

public class VerifierDeploymentReadyCondition
        implements Condition<Deployment, CloudEventSigningConsumerPolicy> {

    @Override
    public boolean isMet(DependentResource<Deployment, CloudEventSigningConsumerPolicy> dependentResource,
                         CloudEventSigningConsumerPolicy primary,
                         Context<CloudEventSigningConsumerPolicy> context) {
        return dependentResource.getSecondaryResource(primary, context)
                .map(deployment -> {
                    DeploymentStatus status = deployment.getStatus();
                    return status != null
                            && status.getAvailableReplicas() != null
                            && status.getAvailableReplicas() >= 1;
                })
                .orElse(false);
    }
}
