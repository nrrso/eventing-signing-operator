// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * Watches the cluster-scoped PublicKeyRegistry singleton and all FederatedKeyRegistry resources,
 * populating the RegistryKeyCache with composite (cluster, keyId) keys. Only active in verify mode.
 */
@ApplicationScoped
public class RegistryWatcher {

    private static final Logger LOG = Logger.getLogger(RegistryWatcher.class);
    private static final String SINGLETON_NAME = "ce-signing-registry";

    private static final ResourceDefinitionContext PKR_RDC =
            new ResourceDefinitionContext.Builder()
                    .withGroup("ce-signing.platform.io")
                    .withVersion("v1alpha1")
                    .withPlural("publickeyregistries")
                    .withNamespaced(false)
                    .build();

    private static final ResourceDefinitionContext FKR_RDC =
            new ResourceDefinitionContext.Builder()
                    .withGroup("ce-signing.platform.io")
                    .withVersion("v1alpha1")
                    .withPlural("federatedkeyregistries")
                    .withNamespaced(false)
                    .build();

    @Inject KubernetesClient client;

    @Inject RegistryKeyCache keyCache;

    @Inject ProxyConfig config;

    private SharedIndexInformer<GenericKubernetesResource> pkrInformer;
    private SharedIndexInformer<GenericKubernetesResource> fkrInformer;

    // Track entries by source to merge local and federated keys
    private final ConcurrentHashMap<String, Map<RegistryKeyCache.CacheKey, PublicKeyEntry>>
            entriesBySource = new ConcurrentHashMap<>();

    void onStart(@Observes StartupEvent ev) {
        if (!"verify".equals(config.mode())) {
            return;
        }

        LOG.info("Starting PublicKeyRegistry watcher");
        pkrInformer =
                client.genericKubernetesResources(PKR_RDC)
                        .withName(SINGLETON_NAME)
                        .inform(
                                new ResourceEventHandler<>() {
                                    @Override
                                    public void onAdd(GenericKubernetesResource resource) {
                                        syncFromResource("pkr", resource);
                                    }

                                    @Override
                                    public void onUpdate(
                                            GenericKubernetesResource oldResource,
                                            GenericKubernetesResource newResource) {
                                        syncFromResource("pkr", newResource);
                                    }

                                    @Override
                                    public void onDelete(
                                            GenericKubernetesResource resource,
                                            boolean deletedFinalStateUnknown) {
                                        entriesBySource.remove("pkr");
                                        mergeAndReplace();
                                        LOG.warn(
                                                "PublicKeyRegistry deleted, local entries cleared");
                                    }
                                },
                                30_000L);

        LOG.info("Starting FederatedKeyRegistry watcher");
        fkrInformer =
                client.genericKubernetesResources(FKR_RDC)
                        .inform(
                                new ResourceEventHandler<>() {
                                    @Override
                                    public void onAdd(GenericKubernetesResource resource) {
                                        syncFromResource(fkrSourceKey(resource), resource);
                                    }

                                    @Override
                                    public void onUpdate(
                                            GenericKubernetesResource oldResource,
                                            GenericKubernetesResource newResource) {
                                        syncFromResource(fkrSourceKey(newResource), newResource);
                                    }

                                    @Override
                                    public void onDelete(
                                            GenericKubernetesResource resource,
                                            boolean deletedFinalStateUnknown) {
                                        entriesBySource.remove(fkrSourceKey(resource));
                                        mergeAndReplace();
                                        LOG.infof(
                                                "FederatedKeyRegistry %s deleted, entries removed",
                                                resource.getMetadata().getName());
                                    }
                                },
                                30_000L);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (pkrInformer != null) {
            pkrInformer.close();
        }
        if (fkrInformer != null) {
            fkrInformer.close();
        }
    }

    private static String fkrSourceKey(GenericKubernetesResource resource) {
        return "fkr:" + resource.getMetadata().getName();
    }

    @SuppressWarnings("unchecked")
    private void syncFromResource(String sourceKey, GenericKubernetesResource resource) {
        try {
            Map<String, Object> spec =
                    (Map<String, Object>) resource.getAdditionalProperties().get("spec");
            if (spec == null) {
                entriesBySource.put(sourceKey, Map.of());
                mergeAndReplace();
                return;
            }

            List<Map<String, Object>> entries = (List<Map<String, Object>>) spec.get("entries");
            if (entries == null || entries.isEmpty()) {
                entriesBySource.put(sourceKey, Map.of());
                mergeAndReplace();
                return;
            }

            Map<RegistryKeyCache.CacheKey, PublicKeyEntry> parsed = new HashMap<>();
            for (Map<String, Object> entry : entries) {
                try {
                    String cluster = (String) entry.get("cluster");
                    String keyId = (String) entry.get("keyId");
                    String pem = (String) entry.get("publicKeyPEM");
                    var publicKey = EventVerifier.parsePublicKeyPem(pem);

                    var cacheKey = new RegistryKeyCache.CacheKey(cluster, keyId);
                    parsed.put(
                            cacheKey,
                            new PublicKeyEntry(
                                    cluster,
                                    (String) entry.get("namespace"),
                                    keyId,
                                    publicKey,
                                    (String) entry.get("algorithm"),
                                    parseDateTime(entry.get("createdAt")),
                                    parseDateTime(entry.get("expiresAt")),
                                    (String) entry.get("status")));
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to parse registry entry: %s", entry.get("keyId"));
                }
            }

            entriesBySource.put(sourceKey, parsed);
            mergeAndReplace();
        } catch (Exception e) {
            LOG.error("Failed to sync registry from " + sourceKey, e);
        }
    }

    private void mergeAndReplace() {
        Map<RegistryKeyCache.CacheKey, PublicKeyEntry> merged = new HashMap<>();
        for (Map<RegistryKeyCache.CacheKey, PublicKeyEntry> sourceEntries :
                entriesBySource.values()) {
            merged.putAll(sourceEntries);
        }
        keyCache.replaceAll(merged);
    }

    private static OffsetDateTime parseDateTime(Object value) {
        if (value instanceof String s) {
            return OffsetDateTime.parse(s);
        }
        return null;
    }
}
