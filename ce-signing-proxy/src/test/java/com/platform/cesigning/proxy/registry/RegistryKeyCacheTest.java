// SPDX-License-Identifier: Apache-2.0
package com.platform.cesigning.proxy.registry;

import com.platform.cesigning.proxy.crypto.EventVerifier;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(cache.getEntry("nonexistent").isEmpty());
    }

    @Test
    void replaceAllPopulatesCache() {
        PublicKeyEntry entry = makeEntry("bu-alice", "bu-alice-v1", "active");
        cache.replaceAll(Map.of("bu-alice-v1", entry));

        Optional<PublicKeyEntry> result = cache.getEntry("bu-alice-v1");
        assertTrue(result.isPresent());
        assertEquals("bu-alice", result.get().namespace());
        assertEquals("active", result.get().status());
    }

    @Test
    void replaceAllClearsPreviousEntries() {
        cache.replaceAll(Map.of("old-key", makeEntry("ns", "old-key", "active")));
        cache.replaceAll(Map.of("new-key", makeEntry("ns", "new-key", "active")));

        assertTrue(cache.getEntry("old-key").isEmpty());
        assertTrue(cache.getEntry("new-key").isPresent());
    }

    @Test
    void putAddsEntry() {
        cache.markSynced();
        PublicKeyEntry entry = makeEntry("bu-bob", "bu-bob-v1", "active");
        cache.put("bu-bob-v1", entry);

        assertEquals(1, cache.size());
        assertTrue(cache.getEntry("bu-bob-v1").isPresent());
    }

    @Test
    void removeDeletesEntry() {
        cache.replaceAll(Map.of("key1", makeEntry("ns", "key1", "active")));
        cache.remove("key1");

        assertTrue(cache.getEntry("key1").isEmpty());
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
        PublicKeyEntry e1 = makeEntry("bu-alice", "bu-alice-v1", "active");
        PublicKeyEntry e2 = makeEntry("bu-bob", "bu-bob-v1", "active");
        cache.replaceAll(Map.of("bu-alice-v1", e1, "bu-bob-v1", e2));

        assertEquals(2, cache.size());
        assertTrue(cache.getEntry("bu-alice-v1").isPresent());
        assertTrue(cache.getEntry("bu-bob-v1").isPresent());
    }

    @Test
    void entryIsUsableForVerification() {
        PublicKeyEntry active = makeEntry("ns", "k1", "active");
        PublicKeyEntry rotating = makeEntry("ns", "k2", "rotating");
        PublicKeyEntry expired = makeEntry("ns", "k3", "expired");

        assertTrue(active.isUsableForVerification());
        assertTrue(rotating.isUsableForVerification());
        assertFalse(expired.isUsableForVerification());
    }

    @Test
    void replaceAllNeverExposesEmptyCacheDuringSwap() throws Exception {
        // Pre-populate cache with an entry
        String keyId = "bu-alice-v1";
        cache.replaceAll(Map.of(keyId, makeEntry("bu-alice", keyId, "active")));

        int readerCount = 8;
        int iterations = 200;
        CountDownLatch ready = new CountDownLatch(readerCount);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount + 1);

        // Reader threads: concurrently call getEntry() while replaceAll() runs
        List<Future<List<Optional<PublicKeyEntry>>>> futures = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                List<Optional<PublicKeyEntry>> results = new ArrayList<>();
                for (int j = 0; j < iterations; j++) {
                    results.add(cache.getEntry(keyId));
                }
                return results;
            }));
        }

        // Writer thread: repeatedly call replaceAll() with the same entry
        PublicKeyEntry newEntry = makeEntry("bu-alice", keyId, "rotating");
        ready.await();
        start.countDown();
        for (int i = 0; i < iterations; i++) {
            cache.replaceAll(Map.of(keyId, newEntry));
        }

        executor.shutdown();
        for (Future<List<Optional<PublicKeyEntry>>> future : futures) {
            for (Optional<PublicKeyEntry> result : future.get()) {
                assertTrue(result.isPresent(),
                        "getEntry() must never see empty cache during replaceAll() when key was present");
            }
        }
    }

    private PublicKeyEntry makeEntry(String namespace, String keyId, String status) {
        return new PublicKeyEntry(namespace, keyId, publicKey, "ed25519",
                OffsetDateTime.now(), OffsetDateTime.now().plusDays(90), status);
    }
}
