// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler.dependent.consumer;

import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicySpec;
import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicyStatus;
import com.platform.cesigning.operator.crd.ProxyConfig;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.dependent.DependentResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerDependentResourceTest {

    private CloudEventSigningConsumerPolicy primary;
    @SuppressWarnings("unchecked")
    private Context<CloudEventSigningConsumerPolicy> context = mock(Context.class);

    @BeforeEach
    void setUp() {
        primary = new CloudEventSigningConsumerPolicy();
        var meta = new ObjectMeta();
        meta.setName("test-policy");
        meta.setNamespace("bu-bob");
        primary.setMetadata(meta);

        var spec = new CloudEventSigningConsumerPolicySpec();
        spec.setTrustedNamespaces(List.of("bu-alice", "bu-carol"));
        spec.setRejectUnsigned(true);
        spec.setProxy(new ProxyConfig());
        primary.setSpec(spec);
        primary.setStatus(new CloudEventSigningConsumerPolicyStatus());
    }

    @Test
    void deploymentDesiredSetsVerifyMode() {
        var resource = new VerifyingDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        assertEquals("ce-verifier", deployment.getMetadata().getName());
        assertEquals("bu-bob", deployment.getMetadata().getNamespace());

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        assertEquals("verify", envVars.stream()
                .filter(e -> "CE_SIGNING_MODE".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
        assertEquals("bu-alice,bu-carol", envVars.stream()
                .filter(e -> "CE_SIGNING_TRUSTED_NAMESPACES".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
        assertEquals("true", envVars.stream()
                .filter(e -> "CE_SIGNING_REJECT_UNSIGNED".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
    }

    @Test
    void serviceDesiredCreatesCorrectService() {
        var resource = new VerifyingServiceDependentResource();
        var service = resource.desired(primary, context);

        assertEquals("ce-verifier", service.getMetadata().getName());
        assertEquals("bu-bob", service.getMetadata().getNamespace());
        assertEquals(80, service.getSpec().getPorts().get(0).getPort());
    }

    @Test
    void deploymentDesiredInjectsUserEnvVars() {
        primary.getSpec().getProxy().setEnv(Map.of(
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317",
                "CE_SIGNING_LOG_LEVEL", "DEBUG"));

        var resource = new VerifyingDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        assertEquals("http://otel-collector:4317", envVars.stream()
                .filter(e -> "OTEL_EXPORTER_OTLP_ENDPOINT".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
        assertEquals("DEBUG", envVars.stream()
                .filter(e -> "CE_SIGNING_LOG_LEVEL".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
    }

    @Test
    void deploymentDesiredFiltersReservedEnvKeys() {
        primary.getSpec().getProxy().setEnv(Map.of(
                "CE_SIGNING_MODE", "sign",
                "CE_SIGNING_TRUSTED_NAMESPACES", "evil-ns",
                "OTEL_EXPORTER_OTLP_ENDPOINT", "http://otel-collector:4317"));

        var resource = new VerifyingDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        // Reserved keys keep operator-managed values
        assertEquals("verify", envVars.stream()
                .filter(e -> "CE_SIGNING_MODE".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
        assertEquals("bu-alice,bu-carol", envVars.stream()
                .filter(e -> "CE_SIGNING_TRUSTED_NAMESPACES".equals(e.getName()))
                .findFirst().orElseThrow().getValue());
        // Non-reserved key is passed through
        assertTrue(envVars.stream().anyMatch(e -> "OTEL_EXPORTER_OTLP_ENDPOINT".equals(e.getName())));
        // Reserved keys should not appear twice
        assertEquals(1, envVars.stream().filter(e -> "CE_SIGNING_MODE".equals(e.getName())).count());
    }

    @Test
    void deploymentDesiredNoExtraEnvWhenMapEmpty() {
        var resource = new VerifyingDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        // Only the 3 operator-managed env vars
        assertEquals(3, envVars.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyConditionMetWhenReplicasAvailable() {
        var condition = new VerifierDeploymentReadyCondition();
        var depResource = mock(DependentResource.class);

        var deployment = new Deployment();
        var status = new DeploymentStatus();
        status.setAvailableReplicas(1);
        deployment.setStatus(status);

        when(depResource.getSecondaryResource(primary, context)).thenReturn(Optional.of(deployment));
        assertTrue(condition.isMet(depResource, primary, context));
    }

    @Test
    @SuppressWarnings("unchecked")
    void readyConditionNotMetWhenNoDeployment() {
        var condition = new VerifierDeploymentReadyCondition();
        var depResource = mock(DependentResource.class);

        when(depResource.getSecondaryResource(primary, context)).thenReturn(Optional.empty());
        assertFalse(condition.isMet(depResource, primary, context));
    }

    @Test
    void deploymentDesiredSetsResourceRequestsAndLimits() {
        var resource = new VerifyingDeploymentDependentResource();
        Deployment deployment = resource.desired(primary, context);

        var container = deployment.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertNotNull(container.getResources(), "resources should be set");
        assertNotNull(container.getResources().getRequests().get("cpu"), "cpu request should be set");
        assertNotNull(container.getResources().getRequests().get("memory"), "memory request should be set");
        assertNotNull(container.getResources().getLimits().get("cpu"), "cpu limit should be set");
        assertNotNull(container.getResources().getLimits().get("memory"), "memory limit should be set");
    }

    @Test
    void serviceDesiredSelectorExcludesManagedByLabel() {
        var resource = new VerifyingServiceDependentResource();
        var service = resource.desired(primary, context);

        assertFalse(service.getSpec().getSelector().containsKey("app.kubernetes.io/managed-by"),
                "Service selector must not include managed-by label");
        assertTrue(service.getSpec().getSelector().containsKey("app.kubernetes.io/name"));
        assertTrue(service.getSpec().getSelector().containsKey("app.kubernetes.io/component"));
    }
}
