# Redis Timeout Runbook

## Alert

- Alert name: `RedisTimeout`
- Severity: warning or critical
- Typical signal: cache access timeout, Redis command latency increase, or Redis connection pool exhaustion.

## Symptoms

- Application logs contain `Redis connection timeout`, `Read timed out`, `MOVED`, `ASK`, or `connection pool exhausted`.
- Cache hit rate drops and database traffic rises.
- Redis command latency spikes, especially for large keys or slow commands.
- Business requests become slower even when application CPU is normal.

## First Response

1. Confirm whether timeout happens on one Redis node, one shard, or the whole cluster.
2. Check Redis latency, connected clients, blocked clients, memory usage, and network error count.
3. Check application cache hit rate and Redis connection pool active/pending metrics.
4. Search recent code changes for large key access, full key scans, or new cache patterns.
5. Check whether many keys expired at the same time and caused cache avalanche.

## Root Cause Candidates

### Hot key

Evidence:

- One key or one key prefix receives most traffic.
- One Redis shard has much higher QPS than others.

Actions:

- Add local Caffeine cache for extremely hot read-only data.
- Split hot key into multiple logical shards when possible.
- Add request coalescing to merge concurrent cache misses.

### Big key or slow command

Evidence:

- Slow log contains `HGETALL`, `LRANGE`, `SMEMBERS`, or large payload reads.
- Network output bytes spike.

Actions:

- Split big hash/list/set into smaller keys.
- Replace full reads with paged reads.
- Add size monitoring and block new oversized values.

### Cache avalanche

Evidence:

- Many keys expire in the same time window.
- Database QPS rises sharply after cache miss burst.

Actions:

- Add random TTL jitter.
- Use logical expiration for hot keys.
- Prewarm critical keys before peak traffic.

## Safe Operations

- Do not use `KEYS *` in production diagnosis.
- Do not flush Redis to solve timeout.
- Avoid increasing retry count blindly; it can amplify downstream pressure.

## Verification

- Redis command latency returns to baseline.
- Cache hit rate recovers.
- Database fallback traffic decreases.
- Application timeout count drops.

## Related Evidence Keywords

`Redis connection timeout`, `cache hit rate`, `hot key`, `big key`, `slowlog`, `pool exhausted`, `TTL avalanche`

