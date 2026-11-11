/*
 * Copyright (c) 2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package simple.cache;

import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class SimpleCacheTest {
    @Test
    public void testWithValueLoader() {
        SimpleCache<String, String> simpleCache = SimpleCache.builder().build(s -> UUID.randomUUID().toString());
        assertNull(simpleCache.put("1", "one"));
        assertEquals("one", simpleCache.get("1"));
        assertEquals("one", simpleCache.putIfAbsent("1", "ONE")); // should return the old value
        assertNull(simpleCache.putIfAbsent("2", () -> "two"));
        assertNull(simpleCache.getIfPresent("3"));
        assertNotNull(simpleCache.get("3")); // from value loader
        assertEquals(3, simpleCache.size());
        assertNotNull(simpleCache.remove("3"));
        assertEquals(2, simpleCache.size());
        simpleCache.clear();
    }

    @Test
    public void testLRUEviction() {
        SimpleCache<String, String> simpleCache = SimpleCache.builder().initialCapacity(4).maximumSize(4).build();
        simpleCache.put("1", "one");
        simpleCache.put("2", "two");
        simpleCache.put("3", "three");
        simpleCache.put("4", "four");
        simpleCache.get("1"); // access the 1st key here
        simpleCache.put("5", "five");
        assertNull(simpleCache.getIfPresent("2")); // key 'two' should not be present
        assertEquals(4, simpleCache.size());
        simpleCache.clear();
    }

    @Test
    public void testCacheKeyExpiry() throws InterruptedException {
        SimpleCache<String, String> simpleCache = SimpleCache.builder()
                .expireAfter(500, ChronoUnit.MILLIS).build();
        simpleCache.put("1", "one");
        Thread.sleep(100);
        simpleCache.put("2", "two");
        Thread.sleep(100);
        simpleCache.put("3", "three");
        Thread.sleep(100);
        simpleCache.put("4", "four");
        Thread.sleep(100);
        simpleCache.put("5", "five");
        Thread.sleep(100);
        assertNull(simpleCache.getIfPresent("1")); // key 'one' should not be present
        assertEquals(4, simpleCache.size());
        simpleCache.clear();
    }

    @Test
    public void testCacheKeyRenewal() throws InterruptedException {
        SimpleCache<String, String> simpleCache = SimpleCache.builder()
                .expireAfter(200, ChronoUnit.MILLIS).build();
        simpleCache.put("1", "one");
        Thread.sleep(100);
        assertTrue(simpleCache.renewKey("1"));
        Thread.sleep(100);
        assertNotNull(simpleCache.getIfPresent("1")); // key 'one' should be present
        simpleCache.clear();
    }

    @Test
    public void testAccessBasedExpiry() throws InterruptedException {
        SimpleCache<String, String> simpleCache = SimpleCache.builder()
                .expireAfter(200, ChronoUnit.MILLIS).renewKeyOnAccess(true).build();
        simpleCache.put("1", "one");
        simpleCache.put("2", "two");
        Thread.sleep(100);
        assertEquals("one", simpleCache.get("1"));
        Thread.sleep(100);
        assertNotNull(simpleCache.getIfPresent("1")); // key 'one' should be present
        assertNull(simpleCache.getIfPresent("2")); // key 'two' should be expired by now
        simpleCache.clear();
    }

    @Test
    public void testWriteBasedExpiry() throws InterruptedException {
        SimpleCache<String, String> simpleCache = SimpleCache.builder()
                .expireAfter(200, ChronoUnit.MILLIS).renewKeyOnAccess(false).build();
        simpleCache.put("1", "one");
        simpleCache.put("2", "two");
        Thread.sleep(100);
        assertEquals("one", simpleCache.get("1"));
        Thread.sleep(100);
        assertNull(simpleCache.getIfPresent("1")); // key 'one' should also be expired by now
        assertNull(simpleCache.getIfPresent("2")); // key 'two' should be expired by now
        simpleCache.clear();
    }

    @Test
    public void testBuilderRejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> SimpleCache.builder().initialCapacity(-1));
        assertThrows(IllegalArgumentException.class, () -> SimpleCache.builder().maximumSize(0));
        assertThrows(IllegalArgumentException.class, () -> SimpleCache.builder().maximumSize(-5));
        assertThrows(IllegalArgumentException.class, () -> SimpleCache.builder().expireAfter(0, ChronoUnit.SECONDS));
        assertThrows(IllegalArgumentException.class, () -> SimpleCache.builder().expireAfter(-5, ChronoUnit.SECONDS));
        assertThrows(NullPointerException.class, () -> SimpleCache.builder().expireAfter(5, null));
    }

    @Test
    public void testRemoveClearsExpirationState() {
        SimpleCache<String, String> cache = SimpleCache.builder().expireAfter(200, ChronoUnit.MILLIS).build();

        cache.put("a", "A");
        assertEquals("A", cache.remove("a"));
        assertNull(cache.getIfPresent("a"));
        assertEquals(0, cache.size());

        assertNull(cache.remove("missing"));
        assertFalse(cache.renewKey("missing"));
    }

    @Test
    public void testPutIfAbsentReturnsExistingValue() {
        SimpleCache<String, String> cache = SimpleCache.builder().build();

        assertNull(cache.putIfAbsent("a", "A"));
        assertEquals("A", cache.putIfAbsent("a", "B"));
        assertEquals("A", cache.get("a"));
    }

    @Test
    public void testPutIfAbsentWithSupplierAndLoader() {
        AtomicInteger calls = new AtomicInteger();

        SimpleCache<String, String> cache = SimpleCache.builder().build(k -> {
            calls.incrementAndGet();
            return "loaded:" + k;
        });

        assertEquals("loaded:x", cache.get("x"));
        assertEquals("loaded:x", cache.get("x"));
        assertEquals(1, calls.get());
    }

    @Test
    public void testExpirationDoesNotResurrectExpiredKey() throws InterruptedException {
        SimpleCache<String, String> cache = SimpleCache.builder()
                .expireAfter(100, ChronoUnit.MILLIS)
                .renewKeyOnAccess(false)
                .build();

        cache.put("a", "A");
        Thread.sleep(150);
        assertNull(cache.getIfPresent("a"));
        assertEquals(0, cache.size());
        assertFalse(cache.renewKey("a"));
    }

    @Test
    public void testConcurrentAccessDoesNotThrow() throws Exception {
        SimpleCache<String, String> cache = SimpleCache.builder().expireAfter(200, ChronoUnit.MILLIS).build();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);

            Future<?>[] futures = new Future<?>[32];
            for (int i = 0; i < futures.length; i++) {
                final int idx = i;
                futures[i] = executor.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < 200; j++) {
                            String key = "k" + (idx % 4);
                            cache.put(key, "v" + idx + ":" + j);
                            cache.getIfPresent(key);
                            cache.get(key);
                            cache.size();
                        }
                        return null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            executor.shutdownNow();
        }
    }
}
