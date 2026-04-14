// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import java.util.Map;

@KubernetesDependent
public class SigningPdbDependentResource
        extends CRUDKubernetesDependentResource<
                PodDisruptionBudget, CloudEventSigningProducerPolicy> {

    public SigningPdbDependentResource() {
        super(PodDisruptionBudget.class);
    }

    @Override
    protected PodDisruptionBudget desired(
            CloudEventSigningProducerPolicy primary,
            Context<CloudEventSigningProducerPolicy> context) {
        Map<String, String> labels =
                Map.of(
                        "app.kubernetes.io/name", "ce-signing-proxy",
                        "app.kubernetes.io/component", "signer",
                        "app.kubernetes.io/managed-by", "ce-signing-operator");
        Map<String, String> selector =
                Map.of(
                        "app.kubernetes.io/name", "ce-signing-proxy",
                        "app.kubernetes.io/component", "signer");

        return new PodDisruptionBudgetBuilder()
                .withNewMetadata()
                .withName("ce-signer")
                .withNamespace(primary.getMetadata().getNamespace())
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withMinAvailable(new IntOrString(1))
                .withNewSelector()
                .withMatchLabels(selector)
                .endSelector()
                .endSpec()
                .build();
    }
}
