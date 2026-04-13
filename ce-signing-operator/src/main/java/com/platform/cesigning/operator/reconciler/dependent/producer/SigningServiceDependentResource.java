// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Map;

@KubernetesDependent
public class SigningServiceDependentResource
        extends CRUDKubernetesDependentResource<Service, CloudEventSigningProducerPolicy> {

    private static final String APP_LABEL = "app.kubernetes.io/name";
    private static final String COMPONENT_LABEL = "app.kubernetes.io/component";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";

    public SigningServiceDependentResource() {
        super(Service.class);
    }

    @Override
    protected Service desired(CloudEventSigningProducerPolicy primary,
                              Context<CloudEventSigningProducerPolicy> context) {
        String namespace = primary.getMetadata().getNamespace();
        Map<String, String> labels = Map.of(
                APP_LABEL, "ce-signing-proxy",
                COMPONENT_LABEL, "signer",
                MANAGED_BY, "ce-signing-operator"
        );
        Map<String, String> selector = Map.of(
                APP_LABEL, "ce-signing-proxy",
                COMPONENT_LABEL, "signer"
        );

        return new ServiceBuilder()
                .withNewMetadata()
                    .withName("ce-signer")
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
