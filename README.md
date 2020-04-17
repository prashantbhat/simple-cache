# Simple Cache

A simple in-memory cache implementation in Java.

### Features:
* provides basic caching functionalities like: `put`, `get` and `remove`, etc.
* automatic loading of entries into the cache using value loaders.
* size-based eviction when a maximumSize is exceeded based on access recency.
* time-based expiration of entries, measured since last access or last write.
* all operations on this cache are thread-safe.

### Usage:
```$java
SimpleCache<String, String> simpleCache = SimpleCache.builder()
    .initialCapacity(16).maximumSize(100)
    .expireAfter(200, ChronoUnit.MILLIS)
    .build(s -> UUID.randomUUID().toString());
simpleCache.put("1", "one");
simpleCache.putIfAbsent("2", () -> "two");
simpleCache.get("1"); // returns "one"
simpleCache.getIfPresent("3"); // returns the value generated i.e. a random UUID
simpleCache.remove("3");
simpleCache.clear();
```

#### References
* [Redis](https://redis.io/) - an in-memory data structure project implementing a distributed, in-memory key-value database with optional durability. 
* [Caffeine](https://github.com/ben-manes/caffeine) - A high performance caching library for Java 8

#### License [![][license img]][license]
This library is published under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0)

[license]:LICENSE
[license img]:https://img.shields.io/badge/license-Apache%202-blue.svg
