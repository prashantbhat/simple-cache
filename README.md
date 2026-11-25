# Simple Cache

A thread-safe, in-memory cache for Java with optional value loading, LRU size limits, and time-based expiration.

### Features

- Basic operations: `put`, `putIfAbsent`, `get`, `getIfPresent`, `remove`, and `clear`.
- Optional value loader for loading a missing entry through `get`.
- Optional maximum size with least-recently-used (LRU) eviction.
- Optional expiration after a configured duration; expiration can be renewed on access.
- Thread-safe cache operations.

### Basic usage

```java
import simple.cache.SimpleCache;

SimpleCache<String, String> cache = SimpleCache.<String, String>builder().build();

cache.put("language", "Java");
String language = cache.getIfPresent("language"); // "Java", or null if absent
String previous = cache.putIfAbsent("language", "Kotlin"); // "Java"
cache.remove("language");
cache.clear();
```

`put` returns the previous value, if any. `putIfAbsent` returns an existing value or `null` after inserting the supplied value. The supplier overload only evaluates its supplier when the key is absent.

### Load missing values

Provide a loader when building the cache. `get` invokes it only when the key is absent and stores the loaded value; `getIfPresent` never invokes the loader.

```java
SimpleCache<String, String> cache = SimpleCache.<String, String>builder()
        .build(String::toUpperCase);

String value = cache.get("hello"); // loads and caches "HELLO"
String cached = cache.getIfPresent("hello"); // "HELLO", without loading
```

### Limit size and expire entries

Combine a maximum size with an expiration duration as needed. When the size limit is exceeded, the least-recently-accessed entry is evicted. By default, expiration is measured from the last write; enable `renewKeyOnAccess(true)` for an idle timeout that renews on reads.

```java
import java.time.temporal.ChronoUnit;

SimpleCache<String, String> cache = SimpleCache.<String, String>builder()
        .initialCapacity(32)
        .maximumSize(1_000)
        .expireAfter(10, ChronoUnit.MINUTES)
        .renewKeyOnAccess(true)
        .build(String::toUpperCase);

cache.put("session:42", "active");
cache.get("session:42"); // renews its expiration because access renewal is enabled
cache.renewKey("session:42"); // explicitly renew expiration
cache.cleanup(); // remove expired entries now
```

`renewKeyOnAccess` defaults to `false`. `renewKey(key)` returns whether an entry with expiration metadata was renewed. Expired entries are cleaned during cache operations; `cleanup()` is also available for explicit cleanup. `size()` returns the number of currently retained entries.

### Maven

```xml
<dependency>
    <groupId>io.github.prashantbhat</groupId>
    <artifactId>simple-cache</artifactId>
    <version>0.2.0</version>
</dependency>
```

#### References

- [Redis](https://redis.io/) - an in-memory data structure project implementing a distributed, in-memory key-value database with optional durability.
- [Caffeine](https://github.com/ben-manes/caffeine) - a high-performance caching library for Java.

### License [![][license img]][license]

This library is published under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg
