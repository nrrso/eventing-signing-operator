// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.producer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicySpec;
import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicyStatus;
import com.platform.cesigning.operator.crd.KeyRotationPolicy;
import com.platform.cesigning.operator.crd.ProxyConfig;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import io.javaoperatorsdk.operator.processing.dependent.Creator;
import io.javaoperatorsdk.operator.processing.dependent.Updater;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProducerDependentResourceTest {

    private CloudEventSigningProducerPolicy primary;

    @SuppressWarnings("unchecked")
    private Context<CloudEventSigningProducerPolicy> context = mock(Context.class);

    @BeforeEach
    void setUp() {
        primary = new CloudEventSigningProducerPolicy();
        var meta = new ObjectMeta();
        meta.setName("test-policy");
        meta.setNamespace("bu-alice");
        primary.setMetadata(meta);

        var spec = new CloudEventSigningProducerPolicySpec();
        spec.setCanonicalAttributes(List.of("type", "source", "subject"));
        spec.setProxy(new ProxyConfig());
        spec.setKeyRotation(new KeyRotationPolicy());
        primary.setSpec(spec);
        primary.setStatus(new CloudEventSigningProducerPolicyStatus());
    }

    @Test
    void secretDesiredGeneratesNewKeyWhenAbsent() {
        var resource = new SecretDependentResource();
        Secret secret = resource.desired(primary, context);

        assertEquals("ce-signing-key", secret.getMetadata().getName());
        assertEquals("bu-alice", secret.getMetadata().getNamespace());
        assertEquals(
                "bu-alice-v1",
                secret.getMetadata().getLabels().get(SecretDependentResource.KEY_ID_LABEL));
        String createdAt =
                secret.getMetadata().getLabels().get(SecretDependentResource.CREATED_AT_LABEL);
        assertNotNull(createdAt);
        assertTrue(
                createdAt.matches("\\d+"),
                "created-at label must be epoch seconds (digits only), got: " + createdAt);
        assertNotNull(secret.getData().get("private.pem"));
        assertNotNull(secret.getData().get("public.pem"));
    }

    @Test
    void secretImplementsCreatorOnly() {
        assertTrue(
                Creator.class.isAssignableFrom(SecretDependentResource.class),
                "SecretDependentResource must implement Creator");
        assertFalse(
                Updater.class.isAssignableFrom(SecretDependentResource.class),
                "SecretDependentResource must NOT implement Updater — Secret is create-only");
    }

    @Test
    void deploymentDesiredReadsKeyIdFromSecret() {
        Secret secret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName("ce-signing-key")
                        .addToLabels(SecretDependentResource.KEY_ID_LABEL, "bu-alice-v3")
                        .endMetadata()
                        .build();
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.of(secret));

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        assertEquals("ce-signer", deployment.getMetadata().getName());
        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        String keyIdEnv =
                envVars.stream()
                        .filter(e -> "CE_SIGNING_KEY_ID".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        assertEquals("bu-alice-v3", keyIdEnv);
    }

    @Test
    void deploymentDesiredFallsBackWhenNoSecret() {
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.empty());

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        String keyIdEnv =
                envVars.stream()
                        .filter(e -> "CE_SIGNING_KEY_ID".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        assertEquals("bu-alice-v1", keyIdEnv);
    }

    @Test
    void serviceDesiredCreatesCorrectService() {
        var resource = new SigningServiceDependentResource();
        var service = resource.desired(primary, context);

        assertEquals("ce-signer", service.getMetadata().getName());
        assertEquals("bu-alice", service.getMetadata().getNamespace());
        assertEquals(80, service.getSpec().getPorts().get(0).getPort());
    }

    @Test
    void pdbDesiredCreatesWithMinAvailable() {
        var resource = new SigningPdbDependentResource();
        var pdb = resource.desired(primary, context);

        assertEquals("ce-signer", pdb.getMetadata().getName());
        assertEquals(1, pdb.getSpec().getMinAvailable().getIntVal());
    }

    @Test
    void hpaDesiredCreatesWithSpecifiedThresholds() {
        var hpaConfig = new ProxyConfig.HpaConfig();
        hpaConfig.setMinReplicas(3);
        hpaConfig.setMaxReplicas(8);
        primary.getSpec().getProxy().setHpa(hpaConfig);

        var resource = new SigningHpaDependentResource();
        var hpa = resource.desired(primary, context);

        assertEquals("ce-signer", hpa.getMetadata().getName());
        assertEquals(3, hpa.getSpec().getMinReplicas());
        assertEquals(8, hpa.getSpec().getMaxReplicas());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deploymentReadyConditionMetWhenReplicasAvailable() {
        var condition = new DeploymentReadyCondition();
        var depResource = mock(DependentResource.class);

        var deployment = new Deployment();
        var status = new DeploymentStatus();
        status.setAvailableReplicas(2);
        deployment.setStatus(status);

        when(depResource.getSecondaryResource(primary, context))
                .thenReturn(Optional.of(deployment));

        assertTrue(condition.isMet(depResource, primary, context));
    }

    @Test
    void deploymentDesiredInjectsUserEnvVars() {
        Secret secret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName("ce-signing-key")
                        .addToLabels(SecretDependentResource.KEY_ID_LABEL, "bu-alice-v1")
                        .endMetadata()
                        .build();
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.of(secret));

        primary.getSpec()
                .getProxy()
                .setEnv(
                        Map.of(
                                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317",
                                "CE_SIGNING_LOG_LEVEL", "DEBUG"));

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        assertEquals(
                "http://otel-collector:4317",
                envVars.stream()
                        .filter(e -> "OTEL_EXPORTER_OTLP_ENDPOINT".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
        assertEquals(
                "DEBUG",
                envVars.stream()
                        .filter(e -> "CE_SIGNING_LOG_LEVEL".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
    }

    @Test
    void deploymentDesiredFiltersReservedEnvKeys() {
        Secret secret =
                new SecretBuilder()
                        .withNewMetadata()
                        .withName("ce-signing-key")
                        .addToLabels(SecretDependentResource.KEY_ID_LABEL, "bu-alice-v1")
                        .endMetadata()
                        .build();
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.of(secret));

        primary.getSpec()
                .getProxy()
                .setEnv(
                        Map.of(
                                "CE_SIGNING_MODE", "verify",
                                "CE_SIGNING_KEY_ID", "evil-key",
                                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317"));

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        // Reserved keys keep operator-managed values
        assertEquals(
                "sign",
                envVars.stream()
                        .filter(e -> "CE_SIGNING_MODE".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
        assertEquals(
                "bu-alice-v1",
                envVars.stream()
                        .filter(e -> "CE_SIGNING_KEY_ID".equals(e.getName()))
                        .findFirst()
                        .orElseThrow()
                        .getValue());
        // Non-reserved key is passed through
        assertTrue(
                envVars.stream().anyMatch(e -> "OTEL_EXPORTER_OTLP_ENDPOINT".equals(e.getName())));
        // Reserved keys should not appear twice
        assertEquals(
                1, envVars.stream().filter(e -> "CE_SIGNING_MODE".equals(e.getName())).count());
        assertEquals(
                1, envVars.stream().filter(e -> "CE_SIGNING_KEY_ID".equals(e.getName())).count());
    }

    @Test
    void deploymentDesiredNoExtraEnvWhenMapEmpty() {
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.empty());

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        // Only the 5 operator-managed env vars (MODE, KEY_ID, CANONICAL_ATTRS, PRIVATE_KEY_PATH,
        // CLUSTER_NAME)
        assertEquals(5, envVars.size());
    }

    @Test
    void deploymentDesiredSetsResourceRequestsAndLimits() {
        when(context.getSecondaryResource(Secret.class)).thenReturn(Optional.empty());

        var resource = new SigningDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertNotNull(container.getResources(), "resources should be set");
        assertNotNull(
                container.getResources().getRequests().get("cpu"), "cpu request should be set");
        assertNotNull(
                container.getResources().getRequests().get("memory"),
                "memory request should be set");
        assertNotNull(container.getResources().getLimits().get("cpu"), "cpu limit should be set");
        assertNotNull(
                container.getResources().getLimits().get("memory"), "memory limit should be set");
    }

    @Test
    void serviceDesiredSelectorExcludesManagedByLabel() {
        var resource = new SigningServiceDependentResource();
        var service = resource.desired(primary, context);

        assertFalse(
                service.getSpec().getSelector().containsKey("app.kubernetes.io/managed-by"),
                "Service selector must not include managed-by label");
        assertTrue(service.getSpec().getSelector().containsKey("app.kubernetes.io/name"));
        assertTrue(service.getSpec().getSelector().containsKey("app.kubernetes.io/component"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deploymentReadyConditionNotMetWhenNoReplicas() {
        var condition = new DeploymentReadyCondition();
        var depResource = mock(DependentResource.class);

        var deployment = new Deployment();
        deployment.setStatus(new DeploymentStatus());

        when(depResource.getSecondaryResource(primary, context))
                .thenReturn(Optional.of(deployment));

        assertFalse(condition.isMet(depResource, primary, context));
    }
}
