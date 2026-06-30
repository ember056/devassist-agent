# MQ Backlog Runbook

## Alert

- Alert name: `MessageQueueBacklog`
- Severity: warning or critical
- Typical signal: consumer lag keeps increasing and message processing latency exceeds the SLO.

## Symptoms

- Kafka consumer lag or RocketMQ accumulation rises continuously.
- Producer is healthy, but consumer throughput is lower than incoming traffic.
- Business data is delayed, duplicated, or processed out of order.
- Consumer logs show timeout, retry, poison message, or downstream dependency failures.

## First Response

1. Confirm affected topic, consumer group, partition, and backlog growth rate.
2. Compare producer rate with consumer rate.
3. Check consumer error logs and retry queue.
4. Check downstream dependencies used by consumers, such as database, Redis, or HTTP services.
5. Confirm whether a recent deployment changed consumer concurrency or message schema.

## Root Cause Candidates

### Consumer capacity insufficient

Evidence:

- No large error rate, but consumer rate is lower than producer rate.
- CPU or thread pool saturation is observed on consumer instances.

Actions:

- Scale out consumers if partition count allows.
- Increase consumer thread pool carefully.
- Reduce non-critical work in the consumer path.

### Downstream dependency slow

Evidence:

- Consumer processing time increases.
- Database, Redis, or HTTP dependency timeout appears in logs.

Actions:

- Apply backpressure and rate limiting.
- Degrade non-critical downstream calls.
- Fix downstream bottleneck before increasing consumer concurrency.

### Poison message or schema mismatch

Evidence:

- Same message repeatedly fails.
- Logs contain deserialization error or validation error.

Actions:

- Move poison messages to DLQ after max retry.
- Add schema compatibility validation.
- Replay messages after code or data fix.

## Safe Operations

- Do not reset offsets before backing up current offset and confirming business impact.
- Do not scale consumers beyond partition limits and downstream capacity.
- Do not ignore duplicate processing risk when replaying messages.

## Verification

- Consumer lag decreases at a stable rate.
- Retry queue stops growing.
- Processing latency returns to SLO.
- Downstream dependency metrics stay healthy after recovery.

## Related Evidence Keywords

`consumer lag`, `message backlog`, `retry queue`, `DLQ`, `consumer timeout`, `partition`, `offset reset`

