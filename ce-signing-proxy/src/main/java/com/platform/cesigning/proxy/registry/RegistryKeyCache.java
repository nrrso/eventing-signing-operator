// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jboss.logging.Logger;

/**
 * In-memory cache of public keys from PublicKeyRegistry and FederatedKeyRegistry. Uses composite
 * key (cluster, keyId) to support multi-cluster key resolution. Populated by Fabric8
 * informer/Watch.
 */
@ApplicationScoped
public class RegistryKeyCache {

    private static final Logger LOG = Logger.getLogger(RegistryKeyCache.class);

    public record CacheKey(String cluster, String keyId) {}

    private volatile ConcurrentMap<CacheKey, PublicKeyEntry> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean synced = new AtomicBoolean(false);

    public Optional<PublicKeyEntry> getEntry(String cluster, String keyId) {
        return Optional.ofNullable(cache.get(new CacheKey(cluster, keyId)));
    }

    public boolean isSynced() {
        return synced.get();
    }

    /** Replace all entries in the cache. Called by the informer on initial LIST and on updates. */
    public void replaceAll(java.util.Map<CacheKey, PublicKeyEntry> entries) {
        cache = new ConcurrentHashMap<>(entries);
        synced.set(true);
        LOG.infof("Registry cache updated: %d entries", entries.size());
    }

    /** Update a single entry. Called on incremental Watch events. */
    public void put(CacheKey key, PublicKeyEntry entry) {
        cache.put(key, entry);
    }

    /** Remove an entry. */
    public void remove(CacheKey key) {
        cache.remove(key);
    }

    /** Mark as synced after initial LIST completes. */
    public void markSynced() {
        synced.set(true);
    }

    public int size() {
        return cache.size();
    }
}
