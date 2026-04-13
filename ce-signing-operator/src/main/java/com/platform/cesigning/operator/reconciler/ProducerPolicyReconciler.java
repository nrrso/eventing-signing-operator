// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicy;
import com.platform.cesigning.operator.crd.CloudEventSigningProducerPolicyStatus;
import com.platform.cesigning.operator.crd.ProducerEntry;
import com.platform.cesigning.operator.crd.PublicKeyEntry;
import com.platform.cesigning.operator.crd.PublicKeyRegistry;
import com.platform.cesigning.operator.crd.PublicKeyRegistrySpec;
import com.platform.cesigning.operator.reconciler.dependent.producer.DeploymentReadyCondition;
import com.platform.cesigning.operator.reconciler.dependent.producer.HpaEnabledCondition;
import com.platform.cesigning.operator.reconciler.dependent.producer.SecretDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.producer.SigningDeploymentDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.producer.SigningHpaDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.producer.SigningPdbDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.producer.SigningServiceDependentResource;
import com.platform.cesigning.operator.reconciler.dependent.producer.SigningServiceMonitorDependentResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
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
import org.jboss.logging.Logger;

import java.net.HttpURLConnection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Workflow(dependents = {
        @Dependent(name = "secret", type = SecretDependentResource.class),
        @Dependent(name = "deployment", type = SigningDeploymentDependentResource.class,
                dependsOn = "secret",
                readyPostcondition = DeploymentReadyCondition.class),
        @Dependent(name = "service", type = SigningServiceDependentResource.class),
        @Dependent(name = "hpa", type = SigningHpaDependentResource.class,
                dependsOn = "deployment",
                reconcilePrecondition = HpaEnabledCondition.class),
        @Dependent(name = "pdb", type = SigningPdbDependentResource.class,
                dependsOn = "deployment"),
        @Dependent(name = "service-monitor", type = SigningServiceMonitorDependentResource.class)
})
@ControllerConfiguration
public class ProducerPolicyReconciler
        implements Reconciler<CloudEventSigningProducerPolicy>,
                   Cleaner<CloudEventSigningProducerPolicy> {

    private static final Logger LOG = Logger.getLogger(ProducerPolicyReconciler.class);
    private static final int MAX_REGISTRY_RETRIES = 3;

    @Inject
    KubernetesClient client;

    @Override
    public UpdateControl<CloudEventSigningProducerPolicy> reconcile(
            CloudEventSigningProducerPolicy resource,
            Context<CloudEventSigningProducerPolicy> context) {

        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        LOG.infof("Reconciling ProducerPolicy %s/%s", namespace, name);

        CloudEventSigningProducerPolicyStatus status = resource.getStatus();
        if (status == null) {
            status = new CloudEventSigningProducerPolicyStatus();
            resource.setStatus(status);
        }

        boolean statusChanged = false;

        // Read key info from Secret (managed by dependent resource)
        var secretOpt = context.getSecondaryResource(Secret.class);
        if (secretOpt.isPresent()) {
            Secret secret = secretOpt.get();
            java.util.Map<String, String> secretLabels = secret.getMetadata().getLabels();
            String keyId = secretLabels != null
                    ? secretLabels.getOrDefault(SecretDependentResource.KEY_ID_LABEL, namespace + "-v1")
                    : namespace + "-v1";
            if (!keyId.equals(status.getKeyId())) {
                status.setKeyId(keyId);
                statusChanged = true;
            }

            String createdAtLabel = secretLabels != null
                    ? secretLabels.get(SecretDependentResource.CREATED_AT_LABEL) : null;
            if (createdAtLabel != null) {
                OffsetDateTime createdAt = LabelSafeTimestamp.decode(createdAtLabel);
                String createdStr = createdAt.toString();
                int intervalDays = resource.getSpec().getKeyRotation().getIntervalDays();
                String expiresStr = createdAt.plusDays(intervalDays).toString();
                if (!createdStr.equals(status.getKeyCreated())) {
                    status.setKeyCreated(createdStr);
                    status.setKeyExpiresAt(expiresStr);
                    statusChanged = true;
                }
            }

            statusChanged |= status.setCondition("KeyPairReady", "True", "KeyCreated",
                    "Keypair available with ID " + keyId);

            // Detect rotation via previous-key-id label
            String previousKeyId = secretLabels != null
                    ? secretLabels.get(SecretDependentResource.PREVIOUS_KEY_ID_LABEL) : null;

            // Publish public key to registry (with rotation handling and expired entry cleanup)
            String encodedPub = secret.getData().get(SecretDependentResource.PUBLIC_KEY_FIELD);
            String publicKeyPem = new String(Base64.getDecoder().decode(encodedPub));
            boolean registryChanged = publishToRegistry(namespace, keyId, publicKeyPem,
                    resource.getSpec().getKeyRotation().getIntervalDays(),
                    previousKeyId,
                    resource.getSpec().getKeyRotation().getGracePeriodDays());
            statusChanged |= status.setCondition("RegistryPublished", "True", "Published",
                    "Key published to registry");

            // Remove the previous-key-id label after processing
            if (previousKeyId != null) {
                removePreviousKeyIdLabel(namespace);
            }

            if (registryChanged) {
                context.getClient().resource(
                        EventHelper.normalEvent(resource, "RegistryPublished",
                                "Published key " + keyId + " to registry"))
                        .inNamespace(namespace).create();
            }
        } else {
            statusChanged |= status.setCondition("KeyPairReady", "False", "Pending",
                    "Waiting for Secret creation");
        }

        // Create/update Knative Sequences for each producer entry
        OwnerReference ownerRef = buildOwnerReference(resource);
        List<String> expectedSequenceNames = new ArrayList<>();
        for (ProducerEntry producer : resource.getSpec().getProducers()) {
            String seqName = producer.getName() + "-signing-seq";
            expectedSequenceNames.add(seqName);
            GenericKubernetesResource sequence = KnativeResourceHelper.buildSigningSequence(
                    producer.getName(), namespace, "ce-signer", producer.getReply(), ownerRef);
            client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                    .inNamespace(namespace)
                    .resource(sequence)
                    .serverSideApply();
        }
        cleanupOrphanedSequences(namespace, expectedSequenceNames);

        // Check workflow result for deployment readiness
        boolean allReady = context.managedWorkflowAndDependentResourceContext()
                .getWorkflowReconcileResult()
                .map(r -> r.allDependentResourcesReady())
                .orElse(false);

        if (allReady) {
            statusChanged |= status.setCondition("SigningProxyReady", "True", "Available",
                    "Signing proxy deployment has available replicas");
            statusChanged |= status.setCondition("Ready", "True", "Reconciled",
                    "All resources created successfully");
        } else {
            statusChanged |= status.setCondition("SigningProxyReady", "False", "DeploymentNotReady",
                    "Signing proxy deployment not yet available");
            statusChanged |= status.setCondition("Ready", "False", "NotReady",
                    "Not all dependent resources are ready");
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
    public DeleteControl cleanup(CloudEventSigningProducerPolicy resource,
                                 Context<CloudEventSigningProducerPolicy> context) {
        String namespace = resource.getMetadata().getNamespace();
        LOG.infof("Cleaning up ProducerPolicy %s/%s", namespace, resource.getMetadata().getName());

        try {
            updateRegistryWithRetry(registry -> {
                if (registry.getSpec() == null || registry.getSpec().getEntries() == null) {
                    return false;
                }
                List<PublicKeyEntry> entries = new ArrayList<>(registry.getSpec().getEntries());
                boolean removed = entries.removeIf(e -> namespace.equals(e.getNamespace()));
                if (removed) {
                    registry.getSpec().setEntries(entries);
                    LOG.infof("Removed registry entries for namespace %s", namespace);
                }
                return removed;
            });

            context.getClient().resource(
                    EventHelper.normalEvent(resource, "RegistryCleaned",
                            "Removed registry entries for namespace " + namespace))
                    .inNamespace(namespace).create();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to clean registry for namespace %s — will retry", namespace);
            return DeleteControl.noFinalizerRemoval();
        }

        return DeleteControl.defaultDelete();
    }

    @Override
    public ErrorStatusUpdateControl<CloudEventSigningProducerPolicy> updateErrorStatus(
            CloudEventSigningProducerPolicy resource,
            Context<CloudEventSigningProducerPolicy> context,
            Exception e) {
        String namespace = resource.getMetadata().getNamespace();
        String name = resource.getMetadata().getName();
        LOG.errorf(e, "Failed to reconcile ProducerPolicy %s/%s", namespace, name);

        CloudEventSigningProducerPolicyStatus status = resource.getStatus();
        if (status == null) {
            status = new CloudEventSigningProducerPolicyStatus();
            resource.setStatus(status);
        }
        status.setCondition("Ready", "False", "ReconcileFailed", e.getMessage());

        try {
            context.getClient().resource(
                    EventHelper.warningEvent(resource, "ReconcileFailed", e.getMessage()))
                    .inNamespace(namespace).create();
        } catch (Exception eventErr) {
            LOG.warnf(eventErr, "Failed to emit warning event for %s/%s", namespace, name);
        }

        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    /**
     * Publishes the active key to the registry, handles rotation (mark old as rotating),
     * and cleans up expired entries. Returns true if the registry was actually modified.
     */
    private boolean publishToRegistry(String namespace, String keyId, String publicKeyPem,
                                      int intervalDays, String previousKeyId, int gracePeriodDays) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        return updateRegistryWithRetry(registry -> {
            if (registry.getSpec() == null) {
                registry.setSpec(new PublicKeyRegistrySpec());
                registry.getSpec().setEntries(new ArrayList<>());
            }

            List<PublicKeyEntry> entries = new ArrayList<>(registry.getSpec().getEntries());
            boolean changed = false;

            // Mark previous key as rotating (if rotation detected)
            if (previousKeyId != null) {
                for (PublicKeyEntry entry : entries) {
                    if (namespace.equals(entry.getNamespace())
                            && previousKeyId.equals(entry.getKeyId())
                            && "active".equals(entry.getStatus())) {
                        entry.setStatus("rotating");
                        changed = true;
                        LOG.infof("Marked key %s as rotating", previousKeyId);
                    }
                }
            }

            // Clean up expired entries for this namespace
            var iterator = entries.iterator();
            while (iterator.hasNext()) {
                PublicKeyEntry entry = iterator.next();
                if (!namespace.equals(entry.getNamespace())) {
                    continue;
                }
                if ("rotating".equals(entry.getStatus()) && entry.getExpiresAt() != null) {
                    OffsetDateTime gracePeriodEnd = OffsetDateTime.parse(entry.getExpiresAt())
                            .plusDays(gracePeriodDays);
                    if (now.isAfter(gracePeriodEnd)) {
                        iterator.remove();
                        changed = true;
                        LOG.infof("Removed expired key %s from registry", entry.getKeyId());
                    }
                }
            }

            // Mark any stale active entries for this namespace as rotating.
            // Handles double-rotation where previousKeyId label was overwritten
            // before the first rotation's registry write succeeded.
            for (PublicKeyEntry entry : entries) {
                if (namespace.equals(entry.getNamespace())
                        && !keyId.equals(entry.getKeyId())
                        && "active".equals(entry.getStatus())) {
                    entry.setStatus("rotating");
                    if (entry.getExpiresAt() == null) {
                        entry.setExpiresAt(now.plusDays(intervalDays).toString());
                    }
                    changed = true;
                    LOG.infof("Marked stale active key %s as rotating (current: %s)",
                            entry.getKeyId(), keyId);
                }
            }

            // Upsert the active entry only if it doesn't already match
            boolean activeExists = entries.stream().anyMatch(e ->
                    namespace.equals(e.getNamespace())
                    && keyId.equals(e.getKeyId())
                    && publicKeyPem.equals(e.getPublicKeyPEM())
                    && "active".equals(e.getStatus()));

            if (!activeExists) {
                entries.removeIf(e -> namespace.equals(e.getNamespace()) && keyId.equals(e.getKeyId()));
                PublicKeyEntry activeEntry = new PublicKeyEntry(namespace, keyId, publicKeyPem);
                activeEntry.setCreatedAt(now.toString());
                activeEntry.setExpiresAt(now.plusDays(intervalDays).toString());
                activeEntry.setStatus("active");
                activeEntry.setAlgorithm("ed25519");
                entries.add(activeEntry);
                changed = true;
            }

            registry.getSpec().setEntries(entries);
            return changed;
        });
    }

    private void removePreviousKeyIdLabel(String namespace) {
        try {
            Secret secret = client.secrets().inNamespace(namespace)
                    .withName(SecretDependentResource.SECRET_NAME).get();
            if (secret != null && secret.getMetadata().getLabels() != null) {
                secret.getMetadata().getLabels().remove(SecretDependentResource.PREVIOUS_KEY_ID_LABEL);
                client.secrets().inNamespace(namespace).resource(secret).serverSideApply();
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to remove previous-key-id label in namespace %s", namespace);
        }
    }

    /**
     * Updates the PublicKeyRegistry with optimistic concurrency.
     * Reads the registry, applies the modifier, and writes back.
     * Retries on 409 Conflict up to MAX_REGISTRY_RETRIES times.
     *
     * @return true if the registry was modified and written
     */
    boolean updateRegistryWithRetry(RegistryModifier modifier) {
        for (int attempt = 0; attempt < MAX_REGISTRY_RETRIES; attempt++) {
            PublicKeyRegistry registry = client.resources(PublicKeyRegistry.class)
                    .withName(PublicKeyRegistry.SINGLETON_NAME)
                    .get();

            boolean isNew = (registry == null);
            if (isNew) {
                registry = new PublicKeyRegistry();
                registry.getMetadata().setName(PublicKeyRegistry.SINGLETON_NAME);
                registry.setSpec(new PublicKeyRegistrySpec());
                registry.getSpec().setEntries(new ArrayList<>());
            }

            boolean shouldWrite = modifier.apply(registry);
            if (!shouldWrite) {
                return false;
            }

            try {
                if (isNew) {
                    client.resources(PublicKeyRegistry.class)
                            .resource(registry)
                            .create();
                } else {
                    client.resources(PublicKeyRegistry.class)
                            .resource(registry)
                            .update();
                }
                return true;
            } catch (KubernetesClientException e) {
                if (e.getCode() == HttpURLConnection.HTTP_CONFLICT && attempt < MAX_REGISTRY_RETRIES - 1) {
                    LOG.infof("Registry update conflict (attempt %d/%d), retrying",
                            attempt + 1, MAX_REGISTRY_RETRIES);
                    continue;
                }
                throw e;
            }
        }
        return false;
    }

    private OwnerReference buildOwnerReference(CloudEventSigningProducerPolicy resource) {
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
            var existing = client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                    .inNamespace(namespace)
                    .withLabel(KnativeResourceHelper.MANAGED_BY_LABEL, KnativeResourceHelper.MANAGED_BY_VALUE)
                    .withLabel(KnativeResourceHelper.COMPONENT_LABEL, "signer")
                    .list().getItems();
            for (var seq : existing) {
                if (!expectedNames.contains(seq.getMetadata().getName())) {
                    client.genericKubernetesResources(KnativeResourceHelper.SEQUENCE_RDC)
                            .inNamespace(namespace)
                            .withName(seq.getMetadata().getName())
                            .delete();
                    LOG.infof("Deleted orphaned Sequence %s/%s", namespace, seq.getMetadata().getName());
                }
            }
        } catch (Exception e) {
            LOG.warnf(e, "Failed to cleanup orphaned Sequences in namespace %s", namespace);
        }
    }

    @FunctionalInterface
    interface RegistryModifier {
        boolean apply(PublicKeyRegistry registry);
    }
}
