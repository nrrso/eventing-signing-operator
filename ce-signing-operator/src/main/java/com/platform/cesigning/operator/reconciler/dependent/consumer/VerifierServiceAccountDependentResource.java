// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.consumer;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;

import java.util.Map;

@KubernetesDependent
public class VerifierServiceAccountDependentResource
        extends CRUDKubernetesDependentResource<ServiceAccount, CloudEventSigningConsumerPolicy> {

    public static final String SERVICE_ACCOUNT_NAME = "ce-signing-verifier";

    public VerifierServiceAccountDependentResource() {
        super(ServiceAccount.class);
    }

    @Override
    protected ServiceAccount desired(CloudEventSigningConsumerPolicy primary,
                                     Context<CloudEventSigningConsumerPolicy> context) {
        return new ServiceAccountBuilder()
                .withNewMetadata()
                    .withName(SERVICE_ACCOUNT_NAME)
                    .withNamespace(primary.getMetadata().getNamespace())
                    .withLabels(Map.of(
                            "app.kubernetes.io/name", "ce-signing-proxy",
                            "app.kubernetes.io/component", "verifier",
                            "app.kubernetes.io/managed-by", "ce-signing-operator"
                    ))
                .endMetadata()
                .build();
    }
}
