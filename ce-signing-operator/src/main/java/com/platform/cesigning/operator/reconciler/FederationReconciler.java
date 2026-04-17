// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.operator.reconciler;

import com.platform.cesigning.operator.config.OperatorMode;
import com.platform.cesigning.operator.crd.ClusterFederationConfig;
import com.platform.cesigning.operator.crd.ClusterFederationConfigStatus;
import com.platform.cesigning.operator.crd.FederatedKeyRegistry;
import com.platform.cesigning.operator.crd.FederatedKeyRegistrySpec;
import com.platform.cesigning.operator.crd.FederatedKeyRegistryStatus;
import com.platform.cesigning.operator.crd.PublicKeyEntry;
import com.platform.cesigning.operator.crd.PublicKeyRegistry;
import com.platform.cesigning.operator.crd.RemoteClusterEntry;
import com.platform.cesigning.operator.crypto.PemValidator;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ControllerConfiguration(name = "federation-controller")
public class FederationReconciler implements Reconciler<ClusterFederationConfig> {

    private static final Logger LOG = Logger.getLogger(FederationReconciler.class);
    private static final int MAX_RETRY = 3;

    @Inject KubernetesClient client;

    @Inject MeterRegistry meterRegistry;

    @Inject OperatorMode operatorMode;

    private final ConcurrentHashMap<String, RemoteClusterWatch> remoteWatches =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> syncedStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> syncRetryCounters =
            new ConcurrentHashMap<>();

    @Override
    public UpdateControl<ClusterFederationConfig> reconcile(
            ClusterFederationConfig resource, Context<ClusterFederationConfig> context) {

        if (!operatorMode.isFederation()) {
            return UpdateControl.noUpdate();
        }

        LOG.info("Reconciling ClusterFederationConfig");

        ClusterFederationConfigStatus status = resource.getStatus();
        if (status == null) {
            status = new ClusterFederationConfigStatus();
            resource.setStatus(status);
        }

        List<RemoteClusterEntry> desired =
                resource.getSpec() != null ? resource.getSpec().getRemoteClusters() : List.of();
        Set<String> desiredNames = new HashSet<>();
        for (RemoteClusterEntry entry : desired) {
            desiredNames.add(entry.getName());
        }

        // Close watches for removed clusters and delete their FederatedKeyRegistry
        Set<String> toRemove = new HashSet<>(remoteWatches.keySet());
        toRemove.removeAll(desiredNames);
        for (String removed : toRemove) {
            closeAndCleanup(removed);
        }

        // Establish or update watches for desired clusters
        List<ClusterFederationConfigStatus.RemoteClusterStatus> clusterStatuses = new ArrayList<>();
        for (RemoteClusterEntry entry : desired) {
            String clusterName = entry.getName();
            var clusterStatus = new ClusterFederationConfigStatus.RemoteClusterStatus();
            clusterStatus.setName(clusterName);

            if (!remoteWatches.containsKey(clusterName)) {
                try {
                    startWatch(entry, context);
                    clusterStatus.setConnectionState("Connected");
                } catch (Exception e) {
                    LOG.errorf(e, "Failed to connect to remote cluster %s", clusterName);
                    clusterStatus.setConnectionState("Error");
                    clusterStatus.setError(e.getMessage());
                }
            } else {
                RemoteClusterWatch watch = remoteWatches.get(clusterName);
                clusterStatus.setConnectionState(
                        watch.isConnected() ? "Connected" : "Disconnected");
                clusterStatus.setLastSyncTime(
                        watch.lastSyncTime != null ? watch.lastSyncTime.toString() : null);
                clusterStatus.setEntryCount(watch.lastEntryCount);
            }

            clusterStatuses.add(clusterStatus);
        }

        status.setRemoteClusters(clusterStatuses);
        status.setObservedGeneration(resource.getMetadata().getGeneration());

        boolean allConnected =
                clusterStatuses.stream().allMatch(s -> "Connected".equals(s.getConnectionState()));
        if (desired.isEmpty()) {
            status.setCondition(
                    "Ready", "True", "NoRemoteClusters", "No remote clusters configured");
        } else if (allConnected) {
            status.setCondition("Ready", "True", "AllConnected", "All remote clusters connected");
        } else {
            status.setCondition(
                    "Ready", "False", "NotAllConnected", "Some remote clusters not connected");
        }

        return UpdateControl.patchStatus(resource);
    }

    @Override
    public ErrorStatusUpdateControl<ClusterFederationConfig> updateErrorStatus(
            ClusterFederationConfig resource,
            Context<ClusterFederationConfig> context,
            Exception e) {
        LOG.errorf(e, "Failed to reconcile ClusterFederationConfig");
        ClusterFederationConfigStatus status = resource.getStatus();
        if (status == null) {
            status = new ClusterFederationConfigStatus();
            resource.setStatus(status);
        }
        status.setCondition("Ready", "False", "ReconcileFailed", e.getMessage());
        return ErrorStatusUpdateControl.patchStatus(resource);
    }

    private void startWatch(RemoteClusterEntry entry, Context<ClusterFederationConfig> context) {
        String clusterName = entry.getName();
        String secretName = entry.getKubeconfigSecretRef();
        String namespace = context.getClient().getConfiguration().getNamespace();

        // Read kubeconfig from Secret
        Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret == null || secret.getData() == null) {
            throw new IllegalStateException(
                    "Kubeconfig secret " + secretName + " not found or empty");
        }

        String kubeconfigB64 = secret.getData().get("kubeconfig");
        if (kubeconfigB64 == null) {
            kubeconfigB64 = secret.getData().get("value");
        }
        if (kubeconfigB64 == null) {
            throw new IllegalStateException(
                    "Kubeconfig secret " + secretName + " has no 'kubeconfig' or 'value' key");
        }

        String kubeconfig = new String(Base64.getDecoder().decode(kubeconfigB64));
        Config config = Config.fromKubeconfig(kubeconfig);
        KubernetesClient remoteClient = new KubernetesClientBuilder().withConfig(config).build();

        AtomicBoolean synced = new AtomicBoolean(false);
        syncedStatus.put(clusterName, synced);

        // Register metrics
        Gauge.builder("ce_signing_federation_watch_connected", () -> synced.get() ? 1 : 0)
                .tag("cluster", clusterName)
                .register(meterRegistry);
        Gauge.builder(
                        "ce_signing_federation_remote_entries",
                        () -> {
                            RemoteClusterWatch w = remoteWatches.get(clusterName);
                            return w != null ? w.lastEntryCount : 0;
                        })
                .tag("cluster", clusterName)
                .register(meterRegistry);

        SharedIndexInformer<PublicKeyRegistry> informer =
                remoteClient
                        .resources(PublicKeyRegistry.class)
                        .withName(PublicKeyRegistry.SINGLETON_NAME)
                        .inform(
                                new ResourceEventHandler<>() {
                                    @Override
                                    public void onAdd(PublicKeyRegistry obj) {
                                        syncRemoteRegistry(clusterName, obj);
                                    }

                                    @Override
                                    public void onUpdate(
                                            PublicKeyRegistry oldObj, PublicKeyRegistry newObj) {
                                        syncRemoteRegistry(clusterName, newObj);
                                    }

                                    @Override
                                    public void onDelete(
                                            PublicKeyRegistry obj,
                                            boolean deletedFinalStateUnknown) {
                                        syncRemoteRegistry(clusterName, null);
                                    }
                                },
                                30_000L);

        remoteWatches.put(clusterName, new RemoteClusterWatch(remoteClient, informer, clusterName));
        LOG.infof("Started watch for remote cluster %s", clusterName);
    }

    private void syncRemoteRegistry(String clusterName, PublicKeyRegistry registry) {
        try {
            List<PublicKeyEntry> entries = new ArrayList<>();
            if (registry != null
                    && registry.getSpec() != null
                    && registry.getSpec().getEntries() != null) {
                for (PublicKeyEntry entry : registry.getSpec().getEntries()) {
                    // Override cluster field with the remote cluster name
                    PublicKeyEntry fedEntry =
                            new PublicKeyEntry(
                                    clusterName,
                                    entry.getNamespace(),
                                    entry.getKeyId(),
                                    entry.getPublicKeyPEM());
                    fedEntry.setAlgorithm(entry.getAlgorithm());
                    fedEntry.setCreatedAt(entry.getCreatedAt());
                    fedEntry.setExpiresAt(entry.getExpiresAt());
                    fedEntry.setStatus(entry.getStatus());
                    entries.add(fedEntry);
                }
            }

            writeFederatedKeyRegistry(clusterName, entries);

            RemoteClusterWatch watch = remoteWatches.get(clusterName);
            if (watch != null) {
                watch.lastSyncTime = OffsetDateTime.now(ZoneOffset.UTC);
                watch.lastEntryCount = entries.size();
            }

            AtomicBoolean synced = syncedStatus.get(clusterName);
            if (synced != null) {
                synced.set(true);
            }

            // Record last sync metric
            meterRegistry.gauge(
                    "ce_signing_federation_last_sync_seconds",
                    List.of(io.micrometer.core.instrument.Tag.of("cluster", clusterName)),
                    System.currentTimeMillis() / 1000.0);

            syncRetryCounters.computeIfAbsent(clusterName, k -> new AtomicInteger()).set(0);
            LOG.infof("Synced %d entries from remote cluster %s", entries.size(), clusterName);
        } catch (Exception e) {
            int attempt =
                    syncRetryCounters
                            .computeIfAbsent(clusterName, k -> new AtomicInteger())
                            .incrementAndGet();
            LOG.errorf(
                    e, "Failed to sync from remote cluster %s (attempt %d)", clusterName, attempt);
        }
    }

    private void writeFederatedKeyRegistry(String clusterName, List<PublicKeyEntry> entries) {
        String resourceName = clusterName + "-keys";

        for (int attempt = 0; attempt < MAX_RETRY; attempt++) {
            FederatedKeyRegistry existing =
                    client.resources(FederatedKeyRegistry.class).withName(resourceName).get();

            boolean isNew = (existing == null);
            FederatedKeyRegistry fkr;
            if (isNew) {
                fkr = new FederatedKeyRegistry();
                fkr.setMetadata(new ObjectMetaBuilder().withName(resourceName).build());
                fkr.setSpec(new FederatedKeyRegistrySpec());
            } else {
                fkr = existing;
            }

            fkr.getSpec().setEntries(entries);

            try {
                if (isNew) {
                    client.resources(FederatedKeyRegistry.class).resource(fkr).create();
                } else {
                    client.resources(FederatedKeyRegistry.class).resource(fkr).update();
                }

                // Validate entries and update status
                FederatedKeyRegistry forStatus =
                        client.resources(FederatedKeyRegistry.class).withName(resourceName).get();
                if (forStatus != null) {
                    FederatedKeyRegistryStatus fkrStatus = forStatus.getStatus();
                    if (fkrStatus == null) {
                        fkrStatus = new FederatedKeyRegistryStatus();
                        forStatus.setStatus(fkrStatus);
                    }
                    fkrStatus.setLastSyncTime(OffsetDateTime.now(ZoneOffset.UTC).toString());
                    fkrStatus.setEntryCount(entries.size());

                    // Validate each entry's PEM
                    List<FederatedKeyRegistryStatus.InvalidEntry> invalid = new ArrayList<>();
                    for (PublicKeyEntry entry : entries) {
                        PemValidator.Result result =
                                PemValidator.validatePublicKeyPem(entry.getPublicKeyPEM());
                        if (!result.valid()) {
                            invalid.add(
                                    new FederatedKeyRegistryStatus.InvalidEntry(
                                            entry.getKeyId(), result.error()));
                            LOG.warnf(
                                    "Invalid PEM for key %s in %s: %s",
                                    entry.getKeyId(), resourceName, result.error());
                        }
                    }
                    fkrStatus.setInvalidEntries(invalid);

                    int validCount = entries.size() - invalid.size();
                    String countMsg = validCount + " of " + entries.size() + " entries valid";
                    fkrStatus.setCondition("Ready", "True", "Synced", countMsg);
                    if (invalid.isEmpty()) {
                        fkrStatus.setCondition("KeysValid", "True", "AllValid", countMsg);
                    } else {
                        fkrStatus.setCondition("KeysValid", "False", "InvalidEntries", countMsg);
                    }

                    client.resources(FederatedKeyRegistry.class).resource(forStatus).patchStatus();
                }
                return;
            } catch (KubernetesClientException e) {
                if (e.getCode() == HttpURLConnection.HTTP_CONFLICT && attempt < MAX_RETRY - 1) {
                    LOG.infof(
                            "FederatedKeyRegistry %s write conflict (attempt %d/%d), retrying",
                            resourceName, attempt + 1, MAX_RETRY);
                    continue;
                }
                throw e;
            }
        }
    }

    private void closeAndCleanup(String clusterName) {
        RemoteClusterWatch watch = remoteWatches.remove(clusterName);
        if (watch != null) {
            watch.close();
        }
        syncedStatus.remove(clusterName);
        syncRetryCounters.remove(clusterName);

        // Delete the FederatedKeyRegistry resource
        String resourceName = clusterName + "-keys";
        try {
            client.resources(FederatedKeyRegistry.class).withName(resourceName).delete();
            LOG.infof(
                    "Deleted FederatedKeyRegistry %s for removed cluster %s",
                    resourceName, clusterName);
        } catch (Exception e) {
            LOG.warnf(
                    e,
                    "Failed to delete FederatedKeyRegistry %s for cluster %s",
                    resourceName,
                    clusterName);
        }
    }

    @PreDestroy
    void shutdown() {
        for (RemoteClusterWatch watch : remoteWatches.values()) {
            watch.close();
        }
        remoteWatches.clear();
    }

    /** Check if all remote clusters have completed initial sync. */
    public boolean isAllSynced() {
        if (syncedStatus.isEmpty()) {
            return true;
        }
        return syncedStatus.values().stream().allMatch(AtomicBoolean::get);
    }

    private static class RemoteClusterWatch {
        final KubernetesClient remoteClient;
        final SharedIndexInformer<PublicKeyRegistry> informer;
        final String clusterName;
        volatile OffsetDateTime lastSyncTime;
        volatile int lastEntryCount;

        RemoteClusterWatch(
                KubernetesClient remoteClient,
                SharedIndexInformer<PublicKeyRegistry> informer,
                String clusterName) {
            this.remoteClient = remoteClient;
            this.informer = informer;
            this.clusterName = clusterName;
        }

        boolean isConnected() {
            return informer != null && informer.isRunning();
        }

        void close() {
            try {
                if (informer != null) {
                    informer.close();
                }
            } catch (Exception e) {
                LOG.warnf(e, "Error closing informer for cluster %s", clusterName);
            }
            try {
                remoteClient.close();
            } catch (Exception e) {
                LOG.warnf(e, "Error closing client for cluster %s", clusterName);
            }
        }
    }
}
