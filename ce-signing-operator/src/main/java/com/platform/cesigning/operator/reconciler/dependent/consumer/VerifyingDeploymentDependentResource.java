// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.consumer;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import com.platform.cesigning.operator.crd.ProxyConfig;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.CRUDKubernetesDependentResource;
import io.javaoperatorsdk.operator.processing.dependent.kubernetes.KubernetesDependent;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@KubernetesDependent
public class VerifyingDeploymentDependentResource
        extends CRUDKubernetesDependentResource<Deployment, CloudEventSigningConsumerPolicy> {

    private static final String APP_LABEL = "app.kubernetes.io/name";
    private static final String COMPONENT_LABEL = "app.kubernetes.io/component";
    private static final String MANAGED_BY = "app.kubernetes.io/managed-by";

    static final Set<String> RESERVED_ENV_KEYS =
            Set.of(
                    "CE_SIGNING_MODE",
                    "CE_SIGNING_TRUSTED_SOURCES",
                    "CE_SIGNING_REJECT_UNSIGNED",
                    "CE_CLUSTER_NAME");

    @Inject
    @ConfigProperty(name = "cesigning.proxy.image")
    String defaultProxyImage;

    @Inject
    @ConfigProperty(name = "cesigning.cluster.name")
    String clusterName;

    public VerifyingDeploymentDependentResource() {
        super(Deployment.class);
    }

    @Override
    protected Deployment desired(
            CloudEventSigningConsumerPolicy primary,
            Context<CloudEventSigningConsumerPolicy> context) {
        String namespace = primary.getMetadata().getNamespace();
        var spec = primary.getSpec();
        ProxyConfig proxy = spec.getProxy();
        String trustedSources =
                spec.getTrustedSources().stream()
                        .map(ts -> ts.getCluster() + "/" + ts.getNamespace())
                        .collect(Collectors.joining(","));
        Map<String, String> labels = verifierLabels();

        ProxyConfig.TopologyConfig topo = proxy.getTopologySpreadConstraints();

        DeploymentBuilder builder =
                new DeploymentBuilder()
                        .withNewMetadata()
                        .withName("ce-verifier")
                        .withNamespace(namespace)
                        .withLabels(labels)
                        .endMetadata();

        var containerBuilder =
                builder.withNewSpec()
                        .withReplicas(proxy.getReplicas())
                        .withNewSelector()
                        .withMatchLabels(labels)
                        .endSelector()
                        .withNewTemplate()
                        .withNewMetadata()
                        .withLabels(labels)
                        .endMetadata()
                        .withNewSpec()
                        .withServiceAccountName(
                                VerifierServiceAccountDependentResource.SERVICE_ACCOUNT_NAME)
                        .withAutomountServiceAccountToken(true)
                        .addNewContainer()
                        .withName("ce-verifier")
                        .withImage(proxy.getImage() != null ? proxy.getImage() : defaultProxyImage)
                        .addNewPort()
                        .withContainerPort(8090)
                        .withName("http")
                        .endPort()
                        .addNewEnv()
                        .withName("CE_SIGNING_MODE")
                        .withValue("verify")
                        .endEnv()
                        .addNewEnv()
                        .withName("CE_SIGNING_TRUSTED_SOURCES")
                        .withValue(trustedSources)
                        .endEnv()
                        .addNewEnv()
                        .withName("CE_SIGNING_REJECT_UNSIGNED")
                        .withValue(String.valueOf(spec.isRejectUnsigned()))
                        .endEnv()
                        .addNewEnv()
                        .withName("CE_CLUSTER_NAME")
                        .withValue(clusterName)
                        .endEnv();

        proxy.getEnv().entrySet().stream()
                .filter(e -> !RESERVED_ENV_KEYS.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        e ->
                                containerBuilder
                                        .addNewEnv()
                                        .withName(e.getKey())
                                        .withValue(e.getValue())
                                        .endEnv());

        var podSpec =
                containerBuilder
                        .withNewReadinessProbe()
                        .withNewHttpGet()
                        .withPath("/health/ready")
                        .withNewPort(8090)
                        .endHttpGet()
                        .withInitialDelaySeconds(5)
                        .withPeriodSeconds(10)
                        .endReadinessProbe()
                        .withNewLivenessProbe()
                        .withNewHttpGet()
                        .withPath("/health/live")
                        .withNewPort(8090)
                        .endHttpGet()
                        .withInitialDelaySeconds(0)
                        .withPeriodSeconds(30)
                        .endLivenessProbe()
                        .withNewStartupProbe()
                        .withNewHttpGet()
                        .withPath("/health/started")
                        .withNewPort(8090)
                        .endHttpGet()
                        .withInitialDelaySeconds(5)
                        .withPeriodSeconds(5)
                        .withFailureThreshold(24)
                        .endStartupProbe()
                        .addNewVolumeMount()
                        .withName("tmp")
                        .withMountPath("/tmp")
                        .endVolumeMount()
                        .withNewSecurityContext()
                        .withRunAsNonRoot(true)
                        .withReadOnlyRootFilesystem(true)
                        .withAllowPrivilegeEscalation(false)
                        .withNewSeccompProfile()
                        .withType("RuntimeDefault")
                        .endSeccompProfile()
                        .withNewCapabilities()
                        .addAllToDrop(java.util.List.of("ALL"))
                        .endCapabilities()
                        .endSecurityContext()
                        .withNewResources()
                        .addToRequests(
                                "cpu", new Quantity(proxy.getResources().getRequests().getCpu()))
                        .addToRequests(
                                "memory",
                                new Quantity(proxy.getResources().getRequests().getMemory()))
                        .addToLimits("cpu", new Quantity(proxy.getResources().getLimits().getCpu()))
                        .addToLimits(
                                "memory",
                                new Quantity(proxy.getResources().getLimits().getMemory()))
                        .endResources()
                        .endContainer()
                        .addNewVolume()
                        .withName("tmp")
                        .withNewEmptyDir()
                        .endEmptyDir()
                        .endVolume();

        if (topo != null && topo.isEnabled()) {
            podSpec =
                    podSpec.addNewTopologySpreadConstraint()
                            .withMaxSkew(topo.getMaxSkew())
                            .withTopologyKey(topo.getTopologyKey())
                            .withWhenUnsatisfiable("DoNotSchedule")
                            .withNewLabelSelector()
                            .withMatchLabels(labels)
                            .endLabelSelector()
                            .endTopologySpreadConstraint();
        }

        return podSpec.endSpec().endTemplate().endSpec().build();
    }

    private static Map<String, String> verifierLabels() {
        return Map.of(
                APP_LABEL, "ce-signing-proxy",
                COMPONENT_LABEL, "verifier",
                MANAGED_BY, "ce-signing-operator");
    }
}
