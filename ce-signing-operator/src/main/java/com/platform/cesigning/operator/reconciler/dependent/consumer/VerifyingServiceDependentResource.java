// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.consumer;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Map;

@KubernetesDependent
public class VerifyingServiceDependentResource
        extends CRUDKubernetesDependentResource<Service, CloudEventSigningConsumerPolicy> {

    public VerifyingServiceDependentResource() {
        super(Service.class);
    }

    @Override
    protected Service desired(CloudEventSigningConsumerPolicy primary,
                              Context<CloudEventSigningConsumerPolicy> context) {
        String namespace = primary.getMetadata().getNamespace();
        Map<String, String> labels = Map.of(
                "app.kubernetes.io/name", "ce-signing-proxy",
                "app.kubernetes.io/component", "verifier",
                "app.kubernetes.io/managed-by", "ce-signing-operator"
        );
        Map<String, String> selector = Map.of(
                "app.kubernetes.io/name", "ce-signing-proxy",
                "app.kubernetes.io/component", "verifier"
        );

        return new ServiceBuilder()
                .withNewMetadata()
                    .withName("ce-verifier")
                    .withNamespace(namespace)
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withSelector(selector)
                    .addNewPort().withPort(80).withNewTargetPort(8090).withName("http").endPort()
                .endSpec()
                .build();
    }
}
