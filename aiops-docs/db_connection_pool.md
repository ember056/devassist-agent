# Database Connection Pool Exhaustion Runbook

## Alert

- Alert name: `DatabaseConnectionPoolExhausted`
- Severity: critical
- Typical signal: API latency rises, database query timeout increases, and connection pool active count is close to max size.

## Symptoms

- Application logs contain `Database query timeout`, `Connection is not available`, or `SQLTransientConnectionException`.
- Connection pool metrics show high `active`, high `pending`, and low `idle`.
- Database slow query logs or lock wait logs increase at the same time.
- Upstream services may show 5xx errors caused by waiting for database connections.

## First Response

1. Confirm whether the issue is isolated to one service or shared by multiple services.
2. Check application logs for connection acquisition timeout and SQL timeout.
3. Check connection pool metrics: max pool size, active connections, idle connections, wait queue length, and timeout count.
4. Check database health: slow queries, lock waits, CPU, IO, and max connections.
5. Preserve slow SQL samples before restarting the service.

## Root Cause Candidates

### Connection pool too small

Evidence:

- Active connections stay close to max pool size.
- Pending requests keep increasing.
- Database itself has enough capacity.

Actions:

- Temporarily increase max pool size only after confirming database capacity.
- Reduce request concurrency or enable rate limiting.
- Tune connection acquisition timeout to fail fast.

### Slow SQL or lock contention

Evidence:

- Slow query logs increase.
- Lock wait time is high.
- Connection pool is exhausted because requests hold connections too long.

Actions:

- Identify top slow SQL by elapsed time and execution count.
- Add missing indexes or optimize query plans.
- Kill clearly blocked sessions only after confirming transaction impact.

### Connection leak

Evidence:

- Active connections keep increasing even when traffic drops.
- No matching query execution is observed on the database side.

Actions:

- Enable leak detection in HikariCP or the selected pool.
- Check code paths that do not close result sets, statements, or transactions.
- Roll back recent code changes if leak appears after deployment.

## Safe Operations

- Do not blindly restart all instances before preserving logs and pool metrics.
- Do not increase max pool size without checking database max connections.
- Prefer rate limiting and partial traffic shedding when the database is already saturated.

## Verification

- Connection acquisition timeout drops to normal.
- Pool active count returns below 70% of max.
- P95 API latency recovers.
- Slow SQL and lock wait metrics return to baseline.

## Related Evidence Keywords

`Database query timeout`, `connection pool`, `active connections`, `pending threads`, `slow query`, `lock wait`, `HikariPool`

