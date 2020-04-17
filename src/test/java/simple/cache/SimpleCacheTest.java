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

import org.junit.jupiter.api.*;

import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SimpleCacheTest {
    @Test
    void testWithValueLoader() {
        SimpleCache<String, String> simpleCache = SimpleCache.builder().build(s -> UUID.randomUUID().toString());
        assertNull(simpleCache.put("1", "one"));
        assertEquals(simpleCache.get("1"), "one");
        assertEquals(simpleCache.putIfAbsent("1", "ONE"), "one"); // should return the old value
        assertEquals(simpleCache.putIfAbsent("2", () -> "two"), "two");
        assertNull(simpleCache.getIfPresent("3"));
        assertNotNull(simpleCache.get("3")); // from value loader
        assertEquals(simpleCache.size(), 3);
        assertNotNull(simpleCache.remove("3"));
        assertEquals(simpleCache.size(), 2);
        simpleCache.clear();
    }

    @Test
    void testLRUEviction() {
        SimpleCache<String, String> simpleCache = SimpleCache.builder().initialCapacity(4).maximumSize(4).build();
        simpleCache.put("1", "one");
        simpleCache.put("2", "two");
        simpleCache.put("3", "three");
        simpleCache.put("4", "four");
        simpleCache.get("1"); // access the 1st key here
        simpleCache.put("5", "five");
        assertNull(simpleCache.getIfPresent("2")); // key 'two' should not be present
        assertEquals(simpleCache.size(), 4);
        simpleCache.clear();
    }

    @Test
    void testCacheKeyExpiry() throws InterruptedException {
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
        assertEquals(simpleCache.size(), 4);
        simpleCache.clear();
    }

    @Test
    void testCacheKeyRenewal() throws InterruptedException {
        SimpleCache<String, String> simpleCache = SimpleCache.builder()
                .expireAfter(200, ChronoUnit.MILLIS).build();
        simpleCache.put("1", "one");
        Thread.sleep(100);
        assertTrue(simpleCache.renewKey("1"));
        Thread.sleep(100);
        assertNotNull(simpleCache.getIfPresent("1")); // key 'one' should be present
        simpleCache.clear();
    }
}