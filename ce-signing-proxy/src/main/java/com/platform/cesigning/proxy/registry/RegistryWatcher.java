// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import com.platform.cesigning.proxy.config.ProxyConfig;
import com.platform.cesigning.proxy.crypto.EventVerifier;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the cluster-scoped PublicKeyRegistry singleton and populates
 * the RegistryKeyCache. Only active in verify mode.
 */
@ApplicationScoped
public class RegistryWatcher {

    private static final Logger LOG = Logger.getLogger(RegistryWatcher.class);
    private static final String SINGLETON_NAME = "ce-signing-registry";

    private static final ResourceDefinitionContext RDC = new ResourceDefinitionContext.Builder()
            .withGroup("ce-signing.platform.io")
            .withVersion("v1alpha1")
            .withPlural("publickeyregistries")
            .withNamespaced(false)
            .build();

    @Inject
    KubernetesClient client;

    @Inject
    RegistryKeyCache keyCache;

    @Inject
    ProxyConfig config;

    private SharedIndexInformer<GenericKubernetesResource> informer;

    void onStart(@Observes StartupEvent ev) {
        if (!"verify".equals(config.mode())) {
            return;
        }

        LOG.info("Starting PublicKeyRegistry watcher");
        informer = client.genericKubernetesResources(RDC)
                .withName(SINGLETON_NAME)
                .inform(new ResourceEventHandler<>() {
                    @Override
                    public void onAdd(GenericKubernetesResource resource) {
                        syncFromResource(resource);
                    }

                    @Override
                    public void onUpdate(GenericKubernetesResource oldResource,
                                         GenericKubernetesResource newResource) {
                        syncFromResource(newResource);
                    }

                    @Override
                    public void onDelete(GenericKubernetesResource resource, boolean deletedFinalStateUnknown) {
                        keyCache.replaceAll(Map.of());
                        LOG.warn("PublicKeyRegistry deleted, cache cleared");
                    }
                }, 30_000L);
    }

    void onStop(@Observes ShutdownEvent ev) {
        if (informer != null) {
            informer.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void syncFromResource(GenericKubernetesResource resource) {
        try {
            Map<String, Object> spec = (Map<String, Object>) resource.getAdditionalProperties().get("spec");
            if (spec == null) {
                keyCache.replaceAll(Map.of());
                return;
            }

            List<Map<String, Object>> entries = (List<Map<String, Object>>) spec.get("entries");
            if (entries == null || entries.isEmpty()) {
                keyCache.replaceAll(Map.of());
                return;
            }

            Map<String, PublicKeyEntry> parsed = new HashMap<>();
            for (Map<String, Object> entry : entries) {
                try {
                    String keyId = (String) entry.get("keyId");
                    String pem = (String) entry.get("publicKeyPEM");
                    var publicKey = EventVerifier.parsePublicKeyPem(pem);

                    parsed.put(keyId, new PublicKeyEntry(
                            (String) entry.get("namespace"),
                            keyId,
                            publicKey,
                            (String) entry.get("algorithm"),
                            parseDateTime(entry.get("createdAt")),
                            parseDateTime(entry.get("expiresAt")),
                            (String) entry.get("status")
                    ));
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to parse registry entry: %s", entry.get("keyId"));
                }
            }

            keyCache.replaceAll(parsed);
        } catch (Exception e) {
            LOG.error("Failed to sync registry", e);
        }
    }

    private static OffsetDateTime parseDateTime(Object value) {
        if (value instanceof String s) {
            return OffsetDateTime.parse(s);
        }
        return null;
    }
}
