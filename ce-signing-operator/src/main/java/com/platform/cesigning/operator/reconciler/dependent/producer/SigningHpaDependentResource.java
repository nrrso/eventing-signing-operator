// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.ProxyConfig;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscaler;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Map;

@KubernetesDependent
public class SigningHpaDependentResource
        extends CRUDKubernetesDependentResource<HorizontalPodAutoscaler, CloudEventSigningProducerPolicy> {

    public SigningHpaDependentResource() {
        super(HorizontalPodAutoscaler.class);
    }

    @Override
    protected HorizontalPodAutoscaler desired(CloudEventSigningProducerPolicy primary,
                                              Context<CloudEventSigningProducerPolicy> context) {
        ProxyConfig.HpaConfig hpa = primary.getSpec().getProxy().getHpa();
        Map<String, String> labels = Map.of(
                "app.kubernetes.io/name", "ce-signing-proxy",
                "app.kubernetes.io/component", "signer",
                "app.kubernetes.io/managed-by", "ce-signing-operator"
        );

        return new HorizontalPodAutoscalerBuilder()
                .withNewMetadata()
                    .withName("ce-signer")
                    .withNamespace(primary.getMetadata().getNamespace())
                    .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                    .withNewScaleTargetRef()
                        .withApiVersion("apps/v1")
                        .withKind("Deployment")
                        .withName("ce-signer")
                    .endScaleTargetRef()
                    .withMinReplicas(hpa.getMinReplicas())
                    .withMaxReplicas(hpa.getMaxReplicas())
                    .addNewMetric()
                        .withType("Resource")
                        .withNewResource()
                            .withName("cpu")
                            .withNewTarget()
                                .withType("Utilization")
                                .withAverageUtilization(hpa.getTargetCPUUtilization())
                            .endTarget()
                        .endResource()
                    .endMetric()
                .endSpec()
                .build();
    }
}
