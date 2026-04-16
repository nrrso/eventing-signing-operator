// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.config.OperatorMode;
import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningConsumerPolicyStatus;
import com.platform.cesigning.operator.crd.ConsumerTriggerEntry;
import com.platform.cesigning.operator.reconciler.dependent.consumer.VerifierDeploymentReadyCondition;
import com.platform.cesigning.operator.reconciler.dependent.consumer.VerifierServiceAccountDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.consumer.VerifyingDeploymentDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.consumer.VerifyingServiceDependentResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBindingBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Workflow;
import io.javaoperatorsdk.operator.api.reconciler.dependent.Dependent;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

@Workflow(
        dependents = {
            @Dependent(
                    name = "serviceaccount",
                    type = VerifierServiceAccountDependentResource.class),
            @Dependent(
                    name = "deployment",
                    type = VerifyingDeploymentDependentResource.class,
                    readyPostcondition = VerifierDeploymentReadyCondition.class,
                    dependsOn = "serviceaccount"),
            @Dependent(name = "service", type = VerifyingServiceDependentResource.class)
        })
@ControllerConfiguration
public class ConsumerPolicyReconciler
        implements Reconciler<CloudEventSigningConsumerPolicy>,
                Cleaner<CloudEventSigningConsumerPolicy> {

    private static final Logger LOG = Logger.getLogger(ConsumerPolicyReconciler.class);

    @Inject KubernetesClient client;

    @Inject OperatorMode operatorMode;

    @Override
    public UpdateControl<CloudEventSigningConsumerPolicy> reconcile(
            CloudEventSigningConsumerPolicy resource,
            Context<CloudEventSigningConsumerPolicy> context) {

        if (!operatorMode.isLocal()) {
            return UpdateControl.noUpdate();
        }

        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        LOG.infof("Reconciling ConsumerPolicy %s/%s", namespace, name);

        CloudEventSigningConsumerPolicyStatus status = resource.getStatus();
        if (status == null) {
            status = new CloudEventSigningConsumerPolicyStatus();
            resource.setStatus(status);
        }

        boolean statusChanged = false;

        // Ensure ClusterRoleBinding exists for the verifier ServiceAccount
        // (managed manually because cluster-scoped resources cannot have namespace-scoped owners)
        ensureVerifierClusterRoleBinding(namespace);

        // Create/update Knative Sequences and Triggers for each consumer entry
        OwnerReference ownerRef = buildOwnerReference(resource);
        List<String> expectedSequenceNames = new ArrayList<>();
        List<String> expectedTriggerNames = new ArrayList<>();
        List<CloudEventSigningConsumerPolicyStatus.ConsumerTriggerStatus> consumerStatuses =
                new ArrayList<>();

        for (ConsumerTriggerEntry consumer : resource.getSpec().getConsumers()) {
            String seqName = consumer.getName() + "-verify-seq";
            String triggerName = consumer.getName() + "-trigger";
            expectedSequenceNames.add(seqName);
            expectedTriggerNames.add(triggerName);

            // Create verifying Sequence
            GenericKubernetesResource sequence =
                    KnativeResourceHelper.buildVerifyingSequence(
                            consumer.getName(),
                            namespace,
                            "ce-verifier",
                            consumer.getSubscriber(),
                            ownerRef);
            client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                    .inNamespace(namespace)
                    .resource(sequence)
                    .serverSideApply();

            // Create Trigger on broker -> Sequence
            GenericKubernetesResource trigger =
                    KnativeResourceHelper.buildConsumerTrigger(
                            consumer.getName(),
                            namespace,
                            consumer.getBroker(),
                            consumer.getFilter(),
                            seqName,
                            ownerRef);
            client.genericKubernetesResources(KnativeResourceHelper.TRIGGER_RDC)
                    .inNamespace(namespace)
                    .resource(trigger)
                    .serverSideApply();

            var triggerStatus = new CloudEventSigningConsumerPolicyStatus.ConsumerTriggerStatus();
            triggerStatus.setName(consumer.getName());
            triggerStatus.setTriggersReady(true);
            consumerStatuses.add(triggerStatus);
        }

        cleanupOrphanedSequences(namespace, expectedSequenceNames);
        cleanupOrphanedTriggers(namespace, expectedTriggerNames);
        status.setConsumers(consumerStatuses);

        boolean allReady =
                context.managedWorkflowAndDependentResourceContext()
                        .getWorkflowReconcileResult()
                        .map(r -> r.allDependentResourcesReady())
                        .orElse(false);

        if (allReady) {
            statusChanged |=
                    status.setCondition(
                            "VerifyingProxyReady",
                            "True",
                            "Available",
                            "Verifying proxy deployment has available replicas");
            statusChanged |=
                    status.setCondition(
                            "Ready", "True", "Reconciled", "All resources created successfully");
        } else {
            statusChanged |=
                    status.setCondition(
                            "VerifyingProxyReady",
                            "False",
                            "DeploymentNotReady",
                            "Verifying proxy deployment not yet available");
            statusChanged |=
                    status.setCondition(
                            "Ready", "False", "NotReady", "Not all dependent resources are ready");
        }

        Long generation = resource.getMetadata().getGeneration();
        if (!generation.equals(status.getObservedGeneration())) {
            status.setObservedGeneration(generation);
            statusChanged = true;
        }

        if (statusChanged) {
            status.setLastReconciled(OffsetDateTime.now(ZoneOffset.UTC).toString());
            return UpdateControl.patchStatus(resource);
        }
        return UpdateControl.noUpdate();
    }

    @Override
    public DeleteControl cleanup(
            CloudEventSigningConsumerPolicy resource,
            Context<CloudEventSigningConsumerPolicy> context) {
        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        LOG.infof("Cleaning up ConsumerPolicy %s/%s", namespace, name);

        // Delete only this policy's Sequences and Triggers (by name, not all by label).
        // K8s ownerReference GC also handles this, but explicit deletion is faster.
        List<String> ownedSequenceNames = new ArrayList<>();
        List<String> ownedTriggerNames = new ArrayList<>();
        if (resource.getSpec() != null && resource.getSpec().getConsumers() != null) {
            for (ConsumerTriggerEntry consumer : resource.getSpec().getConsumers()) {
                ownedSequenceNames.add(consumer.getName() + "-verify-seq");
                ownedTriggerNames.add(consumer.getName() + "-trigger");
            }
        }
        deleteOwnedSequences(namespace, ownedSequenceNames);
        deleteOwnedTriggers(namespace, ownedTriggerNames);

        // Only delete shared CRB if no other ConsumerPolicy exists in this namespace
        boolean otherPoliciesExist =
                client
                        .resources(CloudEventSigningConsumerPolicy.class)
                        .inNamespace(namespace)
                        .list()
                        .getItems()
                        .stream()
                        .anyMatch(p -> !name.equals(p.getMetadata().getName()));
        if (!otherPoliciesExist) {
            deleteVerifierClusterRoleBinding(namespace);
        } else {
            LOG.infof(
                    "Skipping CRB deletion — other ConsumerPolicies exist in namespace %s",
                    namespace);
        }

        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<CloudEventSigningConsumerPolicy> updateErrorStatus(
            CloudEventSigningConsumerPolicy resource,
            Context<CloudEventSigningConsumerPolicy> context,
            Exception e) {
        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        LOG.errorf(e, "Failed to reconcile ConsumerPolicy %s/%s", namespace, name);

        CloudEventSigningConsumerPolicyStatus status = resource.getStatus();
        if (status == null) {
            status = new CloudEventSigningConsumerPolicyStatus();
            resource.setStatus(status);
        }
        status.setCondition("Ready", "False", "ReconcileFailed", e.getMessage());

        try {
            context.getClient()
                    .resource(EventHelper.warningEvent(resource, "ReconcileFailed", e.getMessage()))
                    .inNamespace(namespace)
                    .create();
        } catch (Exception eventErr) {
            LOG.warnf(eventErr, "Failed to emit warning event for %s/%s", namespace, name);
        }

        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    private OwnerReference buildOwnerReference(CloudEventSigningConsumerPolicy resource) {
        return new OwnerReferenceBuilder()
                .withApiVersion(resource.getApiVersion())
                .withKind(resource.getKind())
                .withName(resource.getMetadata().getName())
                .withUid(resource.getMetadata().getUid())
                .withController(true)
                .build();
    }

    private void cleanupOrphanedSequences(String namespace, List<String> expectedNames) {
        try {
            var existing =
                    client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                            .inNamespace(namespace)
                            .withLabel(
                                    KnativeResourceHelper.MANAGED_BY_LABEL,
                                    KnativeResourceHelper.MANAGED_BY_VALUE)
                            .withLabel(KnativeResourceHelper.COMPONENT_LABEL, "verifier")
                            .list()
                            .getItems();
            for (var seq : existing) {
                if (!expectedNames.contains(seq.getMetadata().getName())) {
                    client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                            .inNamespace(namespace)
                            .withName(seq.getMetadata().getName())
                            .delete();
                    LOG.infof(
                            "Deleted orphaned Sequence %s/%s",
                            namespace, seq.getMetadata().getName());
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to cleanup orphaned Sequences in namespace %s", namespace);
        }
    }

    private void ensureVerifierClusterRoleBinding(String namespace) {
        String bindingName = "ce-signing-verifier-" + namespace;
        ClusterRoleBinding crb =
                new ClusterRoleBindingBuilder()
                        .withNewMetadata()
                        .withName(bindingName)
                        .withLabels(
                                Map.of(
                                        "app.kubernetes.io/name", "ce-signing-proxy",
                                        "app.kubernetes.io/component", "verifier",
                                        "app.kubernetes.io/managed-by", "ce-signing-operator"))
                        .endMetadata()
                        .withNewRoleRef()
                        .withApiGroup("rbac.authorization.k8s.io")
                        .withKind("ClusterRole")
                        .withName("ce-signing-verifier")
                        .endRoleRef()
                        .addNewSubject()
                        .withKind("ServiceAccount")
                        .withName(VerifierServiceAccountDependentResource.SERVICE_ACCOUNT_NAME)
                        .withNamespace(namespace)
                        .endSubject()
                        .build();
        client.rbac().clusterRoleBindings().resource(crb).serverSideApply();
    }

    private void deleteVerifierClusterRoleBinding(String namespace) {
        try {
            String bindingName = "ce-signing-verifier-" + namespace;
            client.rbac().clusterRoleBindings().withName(bindingName).delete();
            LOG.infof("Deleted ClusterRoleBinding %s", bindingName);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to delete ClusterRoleBinding for namespace %s", namespace);
        }
    }

    private void deleteOwnedSequences(String namespace, List<String> names) {
        for (String name : names) {
            try {
                client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                        .inNamespace(namespace)
                        .withName(name)
                        .delete();
                LOG.infof("Deleted Sequence %s/%s", namespace, name);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete Sequence %s/%s", namespace, name);
            }
        }
    }

    private void deleteOwnedTriggers(String namespace, List<String> names) {
        for (String name : names) {
            try {
                client.genericKubernetesResources(KnativeResourceHelper.TRIGGER_RDC)
                        .inNamespace(namespace)
                        .withName(name)
                        .delete();
                LOG.infof("Deleted Trigger %s/%s", namespace, name);
            } catch (Exception e) {
                LOG.warnf(e, "Failed to delete Trigger %s/%s", namespace, name);
            }
        }
    }

    private void cleanupOrphanedTriggers(String namespace, List<String> expectedNames) {
        try {
            var existing =
                    client.genericKubernetesResources(KnativeResourceHelper.TRIGGER_RDC)
                            .inNamespace(namespace)
                            .withLabel(
                                    KnativeResourceHelper.MANAGED_BY_LABEL,
                                    KnativeResourceHelper.MANAGED_BY_VALUE)
                            .withLabel(KnativeResourceHelper.COMPONENT_LABEL, "verifier")
                            .list()
                            .getItems();
            for (var trigger : existing) {
                if (!expectedNames.contains(trigger.getMetadata().getName())) {
                    client.genericKubernetesResources(KnativeResourceHelper.TRIGGER_RDC)
                            .inNamespace(namespace)
                            .withName(trigger.getMetadata().getName())
                            .delete();
                    LOG.infof(
                            "Deleted orphaned Trigger %s/%s",
                            namespace, trigger.getMetadata().getName());
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to cleanup orphaned Triggers in namespace %s", namespace);
        }
    }
}
