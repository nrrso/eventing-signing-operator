// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.platform.cesigning.proxy.crypto.EventVerifier;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistryKeyCacheTest {

    private static Ed25519PublicKeyParameters publicKey;
    private RegistryKeyCache cache;

    @BeforeAll
    static void setUpClass() throws IOException {
        publicKey = EventVerifier.loadPublicKey(Path.of("src/test/resources/test-public.pem"));
    }

    @BeforeEach
    void setUp() {
        cache = new RegistryKeyCache();
    }

    @Test
    void initiallyNotSynced() {
        assertFalse(cache.isSynced());
    }

    @Test
    void replaceAllMarksSynced() {
        cache.replaceAll(Map.of());
        assertTrue(cache.isSynced());
    }

    @Test
    void emptyCacheReturnsEmpty() {
        cache.replaceAll(Map.of());
        assertTrue(cache.getEntry("cluster-east", "nonexistent").isEmpty());
    }

    @Test
    void replaceAllPopulatesCache() {
        PublicKeyEntry entry = makeEntry("cluster-east", "bu-alice", "bu-alice-v1", "active");
        var key = new RegistryKeyCache.CacheKey("cluster-east", "bu-alice-v1");
        cache.replaceAll(Map.of(key, entry));

        Optional<PublicKeyEntry> result = cache.getEntry("cluster-east", "bu-alice-v1");
        assertTrue(result.isPresent());
        assertEquals("bu-alice", result.get().namespace());
        assertEquals("cluster-east", result.get().cluster());
        assertEquals("active", result.get().status());
    }

    @Test
    void replaceAllClearsPreviousEntries() {
        var oldKey = new RegistryKeyCache.CacheKey("cluster-east", "old-key");
        var newKey = new RegistryKeyCache.CacheKey("cluster-east", "new-key");
        cache.replaceAll(Map.of(oldKey, makeEntry("cluster-east", "ns", "old-key", "active")));
        cache.replaceAll(Map.of(newKey, makeEntry("cluster-east", "ns", "new-key", "active")));

        assertTrue(cache.getEntry("cluster-east", "old-key").isEmpty());
        assertTrue(cache.getEntry("cluster-east", "new-key").isPresent());
    }

    @Test
    void putAddsEntry() {
        cache.markSynced();
        PublicKeyEntry entry = makeEntry("cluster-east", "bu-bob", "bu-bob-v1", "active");
        var key = new RegistryKeyCache.CacheKey("cluster-east", "bu-bob-v1");
        cache.put(key, entry);

        assertEquals(1, cache.size());
        assertTrue(cache.getEntry("cluster-east", "bu-bob-v1").isPresent());
    }

    @Test
    void removeDeletesEntry() {
        var key = new RegistryKeyCache.CacheKey("cluster-east", "key1");
        cache.replaceAll(Map.of(key, makeEntry("cluster-east", "ns", "key1", "active")));
        cache.remove(key);

        assertTrue(cache.getEntry("cluster-east", "key1").isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    void markSyncedWithoutReplaceAll() {
        assertFalse(cache.isSynced());
        cache.markSynced();
        assertTrue(cache.isSynced());
    }

    @Test
    void multipleEntries() {
        PublicKeyEntry e1 = makeEntry("cluster-east", "bu-alice", "bu-alice-v1", "active");
        PublicKeyEntry e2 = makeEntry("cluster-east", "bu-bob", "bu-bob-v1", "active");
        var k1 = new RegistryKeyCache.CacheKey("cluster-east", "bu-alice-v1");
        var k2 = new RegistryKeyCache.CacheKey("cluster-east", "bu-bob-v1");
        cache.replaceAll(Map.of(k1, e1, k2, e2));

        assertEquals(2, cache.size());
        assertTrue(cache.getEntry("cluster-east", "bu-alice-v1").isPresent());
        assertTrue(cache.getEntry("cluster-east", "bu-bob-v1").isPresent());
    }

    @Test
    void sameKeyIdDifferentClusters() {
        PublicKeyEntry e1 = makeEntry("cluster-east", "bu-alice", "bu-alice-v1", "active");
        PublicKeyEntry e2 = makeEntry("cluster-west", "bu-alice", "bu-alice-v1", "active");
        var k1 = new RegistryKeyCache.CacheKey("cluster-east", "bu-alice-v1");
        var k2 = new RegistryKeyCache.CacheKey("cluster-west", "bu-alice-v1");
        cache.replaceAll(Map.of(k1, e1, k2, e2));

        assertEquals(2, cache.size());
        assertEquals("cluster-east", cache.getEntry("cluster-east", "bu-alice-v1").get().cluster());
        assertEquals("cluster-west", cache.getEntry("cluster-west", "bu-alice-v1").get().cluster());
    }

    @Test
    void entryIsUsableForVerification() {
        PublicKeyEntry active = makeEntry("c", "ns", "k1", "active");
        PublicKeyEntry rotating = makeEntry("c", "ns", "k2", "rotating");
        PublicKeyEntry expired = makeEntry("c", "ns", "k3", "expired");

        assertTrue(active.isUsableForVerification());
        assertTrue(rotating.isUsableForVerification());
        assertFalse(expired.isUsableForVerification());
    }

    @Test
    void replaceAllNeverExposesEmptyCacheDuringSwap() throws Exception {
        String cluster = "cluster-east";
        String keyId = "bu-alice-v1";
        var key = new RegistryKeyCache.CacheKey(cluster, keyId);
        cache.replaceAll(Map.of(key, makeEntry(cluster, "bu-alice", keyId, "active")));

        int readerCount = 8;
        int iterations = 200;
        CountDownLatch ready = new CountDownLatch(readerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);

        List<Future<List<Optional<PublicKeyEntry>>>> futures = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            futures.add(
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                List<Optional<PublicKeyEntry>> results = new ArrayList<>();
                                for (int j = 0; j < iterations; j++) {
                                    results.add(cache.getEntry(cluster, keyId));
                                }
                                return results;
                            }));
        }

        PublicKeyEntry newEntry = makeEntry(cluster, "bu-alice", keyId, "rotating");
        ready.await();
        start.countDown();
        for (int i = 0; i < iterations; i++) {
            cache.replaceAll(Map.of(key, newEntry));
        }

        executor.shutdown();
        for (Future<List<Optional<PublicKeyEntry>>> future : futures) {
            for (Optional<PublicKeyEntry> result : future.get()) {
                assertTrue(
                        result.isPresent(),
                        "getEntry() must never see empty cache during replaceAll() when key was present");
            }
        }
    }

    private PublicKeyEntry makeEntry(
            String cluster, String namespace, String keyId, String status) {
        return new PublicKeyEntry(
                cluster,
                namespace,
                keyId,
                publicKey,
                "ed25519",
                OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(90),
                status);
    }
}
