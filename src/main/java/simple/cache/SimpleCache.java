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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Simple in-memory cache with LRU eviction policy. All operations on this cache are thread-safe.
 * @param <K> the key type
 * @param <V> the value type
 */
public final class SimpleCache<K, V> {
    private final Map<K, V> cacheMap;
    private final Function<K, V> valueLoader;
    private final ReentrantReadWriteLock rwl;

    // Holds the map keys using the given life time for expiration.
    private final DelayQueue<DelayedKey<K>> delayQueue;
    private final Map<K, DelayedKey<K>> expiringKeys;
    // The default max life time in milliseconds.
    private final long defaultExpiryAfter;
    private final ChronoUnit defaultExpiryUnit;

    private SimpleCache(Map<K, V> cacheMap, Function<K, V> valueLoader,
            long defaultExpiryAfter, ChronoUnit defaultExpiryUnit) {
        this.cacheMap = cacheMap;
        this.valueLoader = valueLoader;
        this.rwl = new ReentrantReadWriteLock();
        this.delayQueue = new DelayQueue<>();
        this.expiringKeys = new HashMap<>();
        this.defaultExpiryAfter = defaultExpiryAfter;
        this.defaultExpiryUnit = defaultExpiryUnit;
    }

    /**
     * Put the given key and value into cache.
     * @param key   the key
     * @param value the value
     * @return a previously associated value, if it was present; can be {@code null}
     */
    public V put(K key, V value) {
        Objects.requireNonNull(key);
        cleanup();
        rwl.writeLock().lock();
        try {
            return internalPutValue(key, value);
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Put the given key and value into cache if the key is not already present.
     * @param key   the key
     * @param value the value
     * @return the existing value if present or the given value; can be {@code null}
     */
    public V putIfAbsent(K key, V value) {
        return putIfAbsent(key, () -> value);
    }

    /**
     * Put the key into cache with the value provided by the given supplier, if key is not already present.
     * @param key           the key
     * @param valueSupplier the value supplier
     * @return the existing value if present or the given value by supplier; can be {@code null}
     */
    public V putIfAbsent(K key, Supplier<V> valueSupplier) {
        Objects.requireNonNull(key);
        cleanup();
        rwl.readLock().lock();
        try {
            V value = cacheMap.get(key);
            if (value == null && valueSupplier != null) { // cache miss
                rwl.readLock().unlock();// Must release read lock before acquiring write lock
                rwl.writeLock().lock();
                try {// recheck state because another thread might have
                    // acquired write lock and changed state before we did.
                    value = cacheMap.get(key);
                    if (value == null) { // not present in the cache
                        value = valueSupplier.get();
                        internalPutValue(key, value);
                    }
                } finally { // downgrade by acquiring read lock before releasing write lock
                    rwl.readLock().lock();
                    rwl.writeLock().unlock(); // unlock write, still hold read
                }
            }
            return value;
        } finally {
            rwl.readLock().unlock();
        }
    }

    /**
     * Get the value in the cache if present for the given key.
     * @param key the key
     * @return the value if present; can be {@code null}
     */
    public V getIfPresent(K key) {
        return doGetValue(key, false);
    }

    /**
     * Get the value in the cache for the given key. If the value is {@code null}, it tries to load it from
     * the @{code valueLoader} if configured.
     * @param key the key
     * @return the value from the cache or from value loader
     */
    public V get(K key) {
        return doGetValue(key, true);
    }

    private V doGetValue(K key, boolean loadIfAbsent) {
        Objects.requireNonNull(key);
        cleanup();
        rwl.readLock().lock();
        try {
            V value = cacheMap.get(key);
            if (value == null && loadIfAbsent && valueLoader != null) { // cache miss
                rwl.readLock().unlock();// must release read lock before acquiring write lock
                rwl.writeLock().lock();
                try {// recheck state because another thread might have
                    // acquired write lock and changed state before we did.
                    value = cacheMap.get(key);
                    if (value == null) { // not present in the cache
                        value = valueLoader.apply(key);
                        internalPutValue(key, value);
                    }
                } finally { // downgrade by acquiring read lock before releasing write lock
                    rwl.readLock().lock();
                    rwl.writeLock().unlock(); // unlock write, still hold read
                }
            } else {
                renewKey(key);
            }
            return value;
        } finally {
            rwl.readLock().unlock();
        }
    }

    private V internalPutValue(K key, V value) {
        if(defaultExpiryAfter > 0) {
            DelayedKey<K> delayedKey = new DelayedKey<>(key, defaultExpiryAfter, defaultExpiryUnit);
            DelayedKey<K> oldKey = expiringKeys.put(key, delayedKey);
            if (oldKey != null) {
                delayQueue.remove(oldKey);
            }
            delayQueue.offer(delayedKey);
        }
        return cacheMap.put(key, value);
    }

    /**
     * Remove the key and value from the cache, if present.
     * @param key the key
     * @return the removed value if present; can be {@code null}
     */
    public V remove(K key) {
        Objects.requireNonNull(key);
        rwl.writeLock().lock();
        try {
            delayQueue.remove(new DelayedKey<>(key));
            expiringKeys.remove(key);
            return cacheMap.remove(key);
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Clear all key-value entries from this cache.
     */
    public void clear() {
        rwl.writeLock().lock();
        try {
            cacheMap.clear();
            delayQueue.clear();
            expiringKeys.clear();
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Get the number of keys in this cache.
     * @return the size of this cache
     */
    public long size() {
        cleanup();
        return cacheMap.size();
    }

    /**
     * Renews the specified key, by setting the life time to the initial value.
     * @param key the key
     * @return {@code true} if the key can be renewed, {@code false} otherwise
     */
    public boolean renewKey(K key) {
        DelayedKey<K> delayedKey = expiringKeys.get(key);
        if (delayedKey != null) {
            delayedKey.renew();
            return true;
        }
        return false;
    }

    /**
     * Cleanup any expired keys.
     */
    public void cleanup() {
        rwl.writeLock().lock();
        try {
            DelayedKey<K> delayedKey = delayQueue.poll();
            while (delayedKey != null) {
                cacheMap.remove(delayedKey.getKey());
                expiringKeys.remove(delayedKey.getKey());
                delayedKey = delayQueue.poll();
            }
        } finally {
            rwl.writeLock().unlock();
        }
    }

    /**
     * Create a new cache builder.
     * @param <K> the key type
     * @param <V> the value type
     * @return a new instance of cache builder
     */
    public static <K, V> CacheBuilder<K, V> builder() {
        return new CacheBuilder<>();
    }

    /**
     * A simple cache builder which allows easier configuration.
     */
    public static final class CacheBuilder<K, V> {
        private int initialCapacity = -1;
        private long maximumSize = -1;
        private long defaultExpiryAfter = 0;
        private ChronoUnit defaultExpiryUnit;

        /**
         * Sets the minimum total size for the internal hash tables.
         * @throws IllegalArgumentException if {@code initialCapacity} is negative
         */
        public CacheBuilder<K, V> initialCapacity(int initialCapacity) {
            if (initialCapacity < 0) {
                throw new IllegalArgumentException("initialCapacity should be >= 0");
            }
            this.initialCapacity = initialCapacity;
            return this;
        }

        /**
         * Sets the maximum total size for the internal hash tables.
         * @throws IllegalArgumentException if {@code maximumSize} is negative
         */
        public CacheBuilder<K, V> maximumSize(long maximumSize) {
            if (maximumSize <= 0) {
                throw new IllegalArgumentException("maximumSize should be greater than zero");
            }
            this.maximumSize = maximumSize;
            return this;
        }

        /**
         * Sets the maximum total size for the internal hash tables.
         * @throws IllegalArgumentException if {@code maximumSize} is negative
         */
        public CacheBuilder<K, V> expireAfter(long expiryAfter, ChronoUnit expiryUnit) {
            if (expiryAfter <= 0) {
                throw new IllegalArgumentException("value for expiryAfter should be greater than zero");
            }
            this.defaultExpiryAfter = expiryAfter;
            this.defaultExpiryUnit = Objects.requireNonNull(expiryUnit);
            return this;
        }

        public <K1 extends K, V1 extends V> SimpleCache<K1, V1> build() {
            return build(null);
        }

        public <K1 extends K, V1 extends V> SimpleCache<K1, V1> build(Function<K1, V1> valueLoader) {
            initialCapacity = Math.max(initialCapacity, 0);
            Map<K1, V1> cacheMap;
            if (maximumSize > 0) {
                // LinkedHashMap as LRU map which uses access order instead of insertion order
                cacheMap = new LinkedHashMap<K1, V1>(initialCapacity, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<K1, V1> eldest) {
                        // remove the eldest entry when map size exceeds the maximum allowed limit
                        return size() > maximumSize;
                    }
                };
            } else {
                cacheMap = new HashMap<>(initialCapacity);
            }
            return new SimpleCache<>(cacheMap, valueLoader, defaultExpiryAfter, defaultExpiryUnit);
        }
    }

    private static class DelayedKey<K> implements Delayed {
        private final K key;
        private long expireAfter;
        private ChronoUnit expiryUnit;
        private Instant startTime;

        public DelayedKey(K key) {
            this.key = key;
        }

        public DelayedKey(K key, long expireAfter, ChronoUnit expiryUnit) {
            this(key);
            this.expiryUnit = expiryUnit;
            this.startTime = Instant.now();
            this.expireAfter = expireAfter;
        }

        public K getKey() {
            return key;
        }

        public void renew() {
            this.startTime = Instant.now();
        }

        @Override
        public long getDelay(TimeUnit timeUnit) {
            long diff = startTime == null ? 0 : Duration.between(Instant.now(),
                    startTime.plus(expireAfter, expiryUnit)).toMillis();
            return timeUnit.convert(diff, TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed that) {
            return Long.compare(this.getDelay(TimeUnit.NANOSECONDS), that.getDelay(TimeUnit.NANOSECONDS));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            } else if (o == null || getClass() != o.getClass()) {
                return false;
            }
            return key.equals(((DelayedKey<?>) o).key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key);
        }
    }
}
