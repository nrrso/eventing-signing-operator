// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory cache of public keys from the cluster-scoped PublicKeyRegistry.
 * Populated by Fabric8 informer/Watch on PublicKeyRegistry singleton.
 */
@ApplicationScoped
public class RegistryKeyCache {

    private static final Logger LOG = Logger.getLogger(RegistryKeyCache.class);

    private volatile ConcurrentMap<String, PublicKeyEntry> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean synced = new AtomicBoolean(false);

    public Optional<PublicKeyEntry> getEntry(String keyId) {
        return Optional.ofNullable(cache.get(keyId));
    }

    public boolean isSynced() {
        return synced.get();
    }

    /**
     * Replace all entries in the cache. Called by the informer on initial LIST and on updates.
     */
    public void replaceAll(java.util.Map<String, PublicKeyEntry> entries) {
        cache = new ConcurrentHashMap<>(entries);
        synced.set(true);
        LOG.infof("Registry cache updated: %d entries", entries.size());
    }

    /**
     * Update a single entry. Called on incremental Watch events.
     */
    public void put(String keyId, PublicKeyEntry entry) {
        cache.put(keyId, entry);
    }

    /**
     * Remove an entry.
     */
    public void remove(String keyId) {
        cache.remove(keyId);
    }

    /**
     * Mark as synced after initial LIST completes.
     */
    public void markSynced() {
        synced.set(true);
    }

    public int size() {
        return cache.size();
    }
}
