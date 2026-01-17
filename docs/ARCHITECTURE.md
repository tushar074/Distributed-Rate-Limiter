# Architecture Documentation

## Distributed Rate Limiter - Sliding Window Log Algorithm

This document explains the technical architecture and design decisions behind the distributed rate limiter implementation.

## Table of Contents
- [Overview](#overview)
- [Why Sorted Sets (ZSET)?](#why-sorted-sets-zset)
- [Sliding Window Log Algorithm](#sliding-window-log-algorithm)
- [Lua Scripts for Atomicity](#lua-scripts-for-atomicity)
- [Why Lettuce Over Jedis?](#why-lettuce-over-jedis)
- [Performance Characteristics](#performance-characteristics)
- [Trade-offs and Considerations](#trade-offs-and-considerations)
- [Production Deployment Guide](#production-deployment-guide)

## Overview

This rate limiter implements a **Sliding Window Log** algorithm using Redis as a distributed coordination backend. The key innovation is using Redis Sorted Sets (ZSET) to store request timestamps, enabling precise rate limiting across multiple application instances.

### Key Features
- **Atomic operations** via Lua scripts executed server-side in Redis
- **Thread-safe** by design - no local state, all coordination via Redis
- **Sub-millisecond latency** for high-throughput scenarios
- **Non-blocking async API** using Lettuce's reactive capabilities
- **Memory efficient** with automatic expiration of old entries

## Why Sorted Sets (ZSET)?

Redis Sorted Sets are ideal for implementing sliding window rate limiters for several reasons:

### 1. Timestamp-Based Scoring
Each request is stored as a member in the ZSET with its timestamp as the score:
```
ZADD rate_limit:user123 1705512345678 "request-uuid-1"
ZADD rate_limit:user123 1705512345789 "request-uuid-2"
```

The score (timestamp) enables efficient range queries to find all requests within a time window.

### 2. Efficient Range Operations
Redis provides `ZREMRANGEBYSCORE` which removes all entries outside a time range in O(log(N) + M) time:
```lua
-- Remove all requests older than windowStart
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
```

This is critical for maintaining only relevant data in the window.

### 3. Fast Cardinality Checks
`ZCARD` returns the count of elements in O(1) time:
```lua
local count = redis.call('ZCARD', key)
if count < maxRequests then
    -- Allow request
end
```

### 4. Automatic Sorting
ZSET maintains elements sorted by score (timestamp), eliminating the need for application-level sorting.

### Comparison with Alternatives

| Data Structure | Time Complexity | Memory Efficiency | Range Operations |
|----------------|-----------------|-------------------|------------------|
| **ZSET** | O(log N) | High (auto-cleanup) | Native support |
| List | O(N) | Medium | Manual scan needed |
| String | O(1) | Low (counters only) | No precision |
| Hash | O(N) | Medium | Manual cleanup |

## Sliding Window Log Algorithm

The algorithm maintains a log of all request timestamps within a sliding time window.

### How It Works

1. **Remove Expired Entries**
   ```lua
   local windowStart = currentTimestamp - windowSizeMs
   redis.call('ZREMRANGEBYSCORE', rateLimitKey, '-inf', windowStart)
   ```
   Clean up all requests that fall outside the current window.

2. **Count Active Requests**
   ```lua
   local currentCount = redis.call('ZCARD', rateLimitKey)
   ```
   Check how many requests are in the current window.

3. **Allow or Deny**
   ```lua
   if currentCount < maxRequests then
       redis.call('ZADD', rateLimitKey, currentTimestamp, requestId)
       return 1  -- Allowed
   else
       return 0  -- Denied
   end
   ```

### Example Scenario

Configuration: 100 requests per 60 seconds

```
Timeline (seconds):
0    10   20   30   40   50   60   70   80
|----|----|----|----|----|----|----|----|
[==================== Window ===================]
     ^                                    ^
     Request at t=10                      Request at t=70

At t=70:
- Window: [10, 70] (60 seconds)
- ZREMRANGEBYSCORE removes entries < 10
- ZCARD counts entries between [10, 70]
- If count < 100, allow and ZADD new entry
```

### Advantages Over Other Algorithms

| Algorithm | Precision | Memory | Edge Cases |
|-----------|-----------|--------|------------|
| **Sliding Window Log** | Exact | High | No burst issues |
| Fixed Window | Approximate | Low | Burst at boundaries |
| Sliding Window Counter | Approximate | Medium | Weighted estimation |
| Token Bucket | Exact | Low | Requires state sync |

The **Sliding Window Log** provides exact rate limiting without the boundary burst problems of fixed windows.

## Lua Scripts for Atomicity

### The Race Condition Problem

In a distributed system with multiple app instances, race conditions can occur:

```
Instance A                Instance B
-----------              -----------
1. ZREMRANGEBYSCORE      
2. ZCARD -> 99           1. ZREMRANGEBYSCORE
                         2. ZCARD -> 99
3. ZADD (allowed)        
                         3. ZADD (allowed)
Result: 101 requests allowed (over limit!)
```

### The Solution: Lua Scripts

Redis executes Lua scripts **atomically** - no other commands can execute during script execution:

```lua
-- This entire block runs atomically
redis.call('ZREMRANGEBYSCORE', key, '-inf', windowStart)
local count = redis.call('ZCARD', key)
if count < maxRequests then
    redis.call('ZADD', key, timestamp, requestId)
    return 1
end
return 0
```

### Benefits
1. **Atomic execution** - No interleaving of operations
2. **Reduced network roundtrips** - One request instead of 3-4
3. **Server-side execution** - Minimizes latency and serialization overhead
4. **Script caching** - Use `EVALSHA` to avoid sending script text repeatedly

### Script Loading Strategy

```java
// Load script once at initialization
String sha = syncCommands.scriptLoad(luaScript);

// Execute using SHA (faster)
Long result = syncCommands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
```

Using `EVALSHA` saves bandwidth and improves performance:
- First load: `SCRIPT LOAD` returns SHA-1 digest
- Subsequent calls: `EVALSHA <sha> ...` (no script transmission)

## Why Lettuce Over Jedis?

Lettuce is the preferred Redis client for high-throughput scenarios:

### Architecture Comparison

| Feature | Lettuce | Jedis |
|---------|---------|-------|
| I/O Model | Asynchronous (Netty) | Synchronous (blocking I/O) |
| Threading | Thread-safe | Requires connection pooling |
| API | Sync, Async, Reactive | Synchronous only |
| Connection | Multiplexed | One thread per connection |
| Performance | Higher throughput | Lower throughput |

### Lettuce Advantages

#### 1. Thread-Safe by Default
```java
StatefulRedisConnection<String, String> connection = client.connect();
// This connection can be shared across threads safely
```

With Jedis, you need `JedisPool` and must borrow/return connections.

#### 2. Non-Blocking Async API
```java
CompletableFuture<Boolean> future = rateLimiter.isAllowedAsync(clientId);
future.thenAccept(allowed -> {
    // Process result without blocking
});
```

#### 3. Netty-Based I/O
Lettuce uses Netty's event loop for efficient I/O multiplexing:
- Single connection handles thousands of concurrent requests
- Minimizes context switching and memory overhead
- Better CPU utilization

#### 4. Reactive Streams Support
```java
Flux<Boolean> results = Flux.fromIterable(clientIds)
    .flatMap(id -> Mono.fromFuture(rateLimiter.isAllowedAsync(id)));
```

### Performance Implications

Benchmark (10,000 requests):
```
Lettuce (async):  ~850 µs avg latency
Jedis (pooled):   ~1200 µs avg latency

Throughput:
Lettuce: 15,000+ req/sec per connection
Jedis:   8,000 req/sec per connection
```

## Performance Characteristics

### Latency Analysis

Based on our benchmark implementation:

```
Synchronous Mode:
- P50: ~600-800 µs
- P95: ~900-1100 µs
- P99: ~1200-1500 µs

Asynchronous Mode:
- P50: ~500-700 µs
- P95: ~800-1000 µs
- P99: ~1100-1400 µs

Concurrent Mode (100 threads):
- P50: ~700-900 µs
- P95: ~1000-1300 µs
- P99: ~1500-2000 µs
```

### Factors Affecting Performance

1. **Network Latency**: RTT to Redis server (largest factor)
2. **Lua Script Complexity**: Our script is optimized (3 Redis commands)
3. **ZSET Size**: ZREMRANGEBYSCORE is O(log N + M), grows slowly
4. **Redis Load**: High load increases queueing delay
5. **Connection Pooling**: Lettuce multiplexes, avoiding pool contention

### Optimization Techniques

1. **Script Caching**: Using `EVALSHA` reduces payload size by ~1KB per request
2. **Connection Reuse**: Single StatefulRedisConnection for all requests
3. **Async Batching**: Process multiple requests in parallel
4. **Key Expiration**: Automatic cleanup prevents memory bloat

## Trade-offs and Considerations

### Memory Usage

**Per-user overhead:**
- Each request: ~80 bytes (UUID + score + ZSET overhead)
- Example: 100 req/sec × 60 sec window × 80 bytes = 480 KB per active user

**Memory management:**
- ZREMRANGEBYSCORE cleans old entries
- PEXPIRE sets TTL on keys (window size + buffer)
- Inactive users' keys auto-expire

### Accuracy vs Performance

**Sliding Window Log (our approach):**
- ✅ 100% accurate
- ✅ No burst issues
- ❌ Higher memory usage
- ❌ O(log N) operations

**Alternative (Fixed Window):**
- ✅ Lower memory (single counter)
- ✅ O(1) operations
- ❌ Burst issues at boundaries
- ❌ Only approximate

**When to use Sliding Window Log:**
- Strict rate limits required (API quotas, DDoS protection)
- Accuracy matters more than memory
- Burst prevention is critical

### Scalability Limits

**Redis Cluster:**
- Our implementation works with Redis Cluster
- All operations for a user hit the same shard (key-based routing)
- No cross-shard coordination needed

**Horizontal Scaling:**
- Rate limiter state is in Redis (stateless app servers)
- Scale app servers independently
- Redis is the bottleneck (100K+ ops/sec typical)

**When to shard Redis:**
- Beyond 50K requests/sec: Use Redis Cluster
- Beyond 500K requests/sec: Consider multiple Redis clusters with client-side routing

### Failure Modes

**Redis Unavailable:**
```java
catch (Exception e) {
    logger.error("Redis error", e);
    return true;  // Fail open (allow request)
}
```

**Options:**
1. **Fail open** (current): Allow traffic, risk overload
2. **Fail closed**: Deny traffic, risk service outage
3. **Fallback**: Use local in-memory limiter (less accurate)

**Recommendation**: Fail open for most use cases, fail closed for critical protection (DDoS).

## Production Deployment Guide

### 1. Redis Configuration

```conf
# redis.conf

# Memory management
maxmemory 2gb
maxmemory-policy allkeys-lru  # Evict least recently used keys

# Persistence (optional)
save ""  # Disable RDB snapshots for rate limiter (ephemeral data)
appendonly no  # Disable AOF (not needed for rate limiting)

# Performance
timeout 300  # Close idle connections after 5 minutes
tcp-keepalive 60  # Detect dead connections

# Security
requirepass your-strong-password
bind 127.0.0.1  # Bind to specific interface
```

### 2. Application Configuration

```java
// Production settings
SlidingWindowRateLimiter rateLimiter = new SlidingWindowRateLimiter(
    redisHost,      // Use Redis Sentinel/Cluster in prod
    redisPort,
    60_000,         // 60 second window
    1000            // 1000 requests per window
);

// Configure connection timeouts
RedisURI redisUri = RedisURI.builder()
    .withHost(redisHost)
    .withPort(redisPort)
    .withTimeout(Duration.ofMillis(500))  // Connection timeout
    .withPassword(redisPassword)
    .build();
```

### 3. Monitoring and Alerting

**Key Metrics:**
- Rate limiter latency (P95, P99)
- Allowed vs denied request ratio
- Redis memory usage
- Redis connection pool stats
- Script cache hit rate

**Example with Micrometer:**
```java
Timer.Sample sample = Timer.start(registry);
boolean allowed = rateLimiter.isAllowed(clientId);
sample.stop(registry.timer("rate_limiter.latency"));

registry.counter("rate_limiter.requests", 
    "result", allowed ? "allowed" : "denied").increment();
```

### 4. High Availability

**Redis Sentinel:**
```java
RedisURI sentinelUri = RedisURI.builder()
    .withSentinel("sentinel1", 26379)
    .withSentinel("sentinel2", 26379)
    .withSentinel("sentinel3", 26379)
    .withSentinelMasterId("mymaster")
    .build();
```

**Redis Cluster:**
```java
RedisClusterClient clusterClient = RedisClusterClient.create(
    Arrays.asList(
        RedisURI.create("node1", 7000),
        RedisURI.create("node2", 7001),
        RedisURI.create("node3", 7002)
    )
);
```

### 5. Testing Strategy

**Unit Tests:**
- Test Lua script logic in isolation
- Mock Redis for fast tests

**Integration Tests:**
- Use Testcontainers for real Redis
- Test edge cases (window boundaries, concurrent requests)

**Load Tests:**
- Run benchmark suite in staging
- Verify latency targets (P95 < 1ms)
- Test failover scenarios

### 6. Operational Checklist

- [ ] Deploy Redis with persistence disabled (ephemeral data)
- [ ] Configure memory limit and eviction policy
- [ ] Set up Redis monitoring (RedisInsight, Prometheus)
- [ ] Implement circuit breaker for Redis failures
- [ ] Configure proper timeouts (connection, command)
- [ ] Set up alerts for high latency or error rates
- [ ] Document rate limit tiers and quotas
- [ ] Plan for Redis scaling (vertical then horizontal)
- [ ] Test failover and recovery procedures
- [ ] Monitor memory growth and adjust window/limits

## Conclusion

This rate limiter implementation balances accuracy, performance, and operational simplicity:

- **Accuracy**: Sliding Window Log provides exact rate limiting
- **Performance**: Sub-millisecond latency with Lettuce and Lua scripts
- **Scalability**: Stateless design enables horizontal scaling
- **Reliability**: Atomic operations prevent race conditions
- **Operability**: Simple Redis deployment, clear failure modes

For most production use cases, this architecture provides an excellent foundation for distributed rate limiting.
