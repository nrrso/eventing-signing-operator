// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import io.fabric8.openshift.api.model.monitoring.v1.ServiceMonitor;
import io.fabric8.openshift.api.model.monitoring.v1.ServiceMonitorBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Map;

@KubernetesDependent
public class SigningServiceMonitorDependentResource
        extends CRUDKubernetesDependentResource<ServiceMonitor, CloudEventSigningProducerPolicy> {

    public SigningServiceMonitorDependentResource() {
        super(ServiceMonitor.class);
    }

    @Override
    protected ServiceMonitor desired(CloudEventSigningProducerPolicy primary,
                                     Context<CloudEventSigningProducerPolicy> context) {
        String namespace = primary.getMetadata().getNamespace();

        return new ServiceMonitorBuilder()
                .withNewMetadata()
                    .withName("ce-signer")
                    .withNamespace(namespace)
                    .addToLabels("app.kubernetes.io/managed-by", "ce-signing-operator")
                .endMetadata()
                .withNewSpec()
                    .withNewSelector()
                        .withMatchLabels(Map.of(
                                "app.kubernetes.io/name", "ce-signing-proxy",
                                "app.kubernetes.io/component", "signer"
                        ))
                    .endSelector()
                    .addNewEndpoint()
                        .withPort("http")
                        .withPath("/q/metrics")
                        .withInterval("30s")
                    .endEndpoint()
                .endSpec()
                .build();
    }
}
