# Pod Restart And CrashLoopBackOff Runbook

## Alert

- Alert name: `PodRestartHigh` or `KubePodCrashLooping`
- Severity: warning or critical
- Typical signal: restart count increases, container exits repeatedly, or pod enters CrashLoopBackOff.

## Symptoms

- Kubernetes events contain `OOMKilled`, `CrashLoopBackOff`, `ImagePullBackOff`, or failed liveness probe.
- Application logs stop shortly after startup.
- Service availability drops if too many replicas restart at the same time.
- CPU or memory metrics may spike before restart.

## First Response

1. Query pod events and container last termination reason.
2. Check previous container logs, not only current logs.
3. Compare restart time with deployment, config change, and traffic spike.
4. Check resource limits and liveness/readiness probe configuration.
5. Confirm whether all replicas are affected or only one node/pod.

## Root Cause Candidates

### OOMKilled

Evidence:

- Last termination reason is `OOMKilled`.
- Memory usage approaches container limit before restart.

Actions:

- Preserve heap dump or GC logs when available.
- Reduce traffic or disable memory-heavy features.
- Tune memory limit and JVM heap ratio.

### Probe misconfiguration

Evidence:

- Liveness probe fails while application can still serve readiness checks later.
- Startup time is longer than probe initial delay.

Actions:

- Increase startup probe or initial delay.
- Separate liveness and readiness semantics.
- Avoid killing slow-starting containers too early.

### Bad deployment or config

Evidence:

- Restart starts immediately after deployment or config update.
- Logs contain missing environment variable, invalid config, or dependency initialization failure.

Actions:

- Roll back deployment or config.
- Validate config in CI before deployment.
- Add startup dependency checks with clear error messages.

## Safe Operations

- Do not delete pods before collecting `describe pod` and previous logs.
- Do not disable probes permanently; adjust them with evidence.
- Do not roll out more replicas if the new version is crashing.

## Verification

- Restart count stops increasing.
- Pods remain ready for at least one observation window.
- Error rate and service latency recover.
- Related deployment or config change is documented.

## Related Evidence Keywords

`CrashLoopBackOff`, `OOMKilled`, `restart_count`, `liveness probe`, `readiness probe`, `previous logs`, `deployment rollback`

