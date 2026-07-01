package org.example.service.aiops;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AiOpsEvidenceRuleService {
    public static final String H_APP_ERROR = "app_error";
    public static final String H_CPU_BOTTLENECK = "cpu_bottleneck";
    public static final String H_MEMORY_PRESSURE = "memory_pressure";
    public static final String H_DISK_PRESSURE = "disk_pressure";
    public static final String H_DB_BOTTLENECK = "db_bottleneck";
    public static final String H_DB_CONNECTION_POOL = "db_connection_pool";
    public static final String H_DOWNSTREAM_DEPENDENCY = "downstream_dependency";
    public static final String H_RUNTIME_RESTART = "runtime_restart";

    public List<EvidenceMatch> match(EvidenceNode evidence) {
        String content = normalize(evidence.getContent());
        List<EvidenceMatch> matches = new ArrayList<>();

        if (containsAny(content, "highcpuusage", "cpu_usage", "cpu")) {
            matches.add(new EvidenceMatch(H_CPU_BOTTLENECK, EvidenceStrength.STRONG_SUPPORT,
                    "CPU alert or CPU metric anomaly is a typical symptom of CPU resource bottleneck."));
        }
        if (containsAny(content, "highmemoryusage", "memory_usage", "oom", "oomkilled")) {
            matches.add(new EvidenceMatch(H_MEMORY_PRESSURE, EvidenceStrength.STRONG_SUPPORT,
                    "Memory alert, OOM, or memory usage anomaly strongly supports memory pressure."));
        }
        if (containsAny(content, "highdiskusage", "disk_usage", "filesystem")) {
            matches.add(new EvidenceMatch(H_DISK_PRESSURE, EvidenceStrength.STRONG_SUPPORT,
                    "Disk alert or disk usage anomaly strongly supports disk pressure."));
        }
        if (containsAny(content, "slowresponse", "database-slow-query", "slow query", "query_time", "query_time_sec")) {
            matches.add(new EvidenceMatch(H_DB_BOTTLENECK, EvidenceStrength.MEDIUM_SUPPORT,
                    "Slow response and slow query evidence points to a database performance bottleneck."));
        }
        if (containsAny(content, "database query timeout", "db connection", "connection timeout", "wait timeout", "database timeout")) {
            matches.add(new EvidenceMatch(H_DB_CONNECTION_POOL, EvidenceStrength.STRONG_SUPPORT,
                    "Database timeout evidence is a high-value signal for connection pool exhaustion or blocked database access."));
            matches.add(new EvidenceMatch(H_DOWNSTREAM_DEPENDENCY, EvidenceStrength.MEDIUM_SUPPORT,
                    "Database timeout also supports a downstream dependency failure hypothesis."));
        }
        if (containsAny(content, "serviceunavailable", "http_status", "500", "level\":\"error", "level: error", "fatal")) {
            matches.add(new EvidenceMatch(H_APP_ERROR, EvidenceStrength.MEDIUM_SUPPORT,
                    "5xx or application error logs support an application failure hypothesis."));
            matches.add(new EvidenceMatch(H_DOWNSTREAM_DEPENDENCY, EvidenceStrength.WEAK_SUPPORT,
                    "5xx can also be caused by dependency failure, but it is not specific by itself."));
        }
        if (containsAny(content, "downstream", "redis", "mq", "third-party", "rpc timeout")) {
            matches.add(new EvidenceMatch(H_DOWNSTREAM_DEPENDENCY, EvidenceStrength.STRONG_SUPPORT,
                    "Downstream timeout or middleware errors strongly support dependency failure."));
        }
        if (containsAny(content, "podrestart", "restart", "crash", "container")) {
            matches.add(new EvidenceMatch(H_RUNTIME_RESTART, EvidenceStrength.STRONG_SUPPORT,
                    "Restart or crash events strongly support runtime instability."));
        }
        if (evidence.getType() == EvidenceType.RUNBOOK && containsAny(content, "pod_restart.md", "crashloopbackoff", "oomkilled", "restart_count")) {
            matches.add(new EvidenceMatch(H_RUNTIME_RESTART, EvidenceStrength.STRONG_SUPPORT,
                    "Pod restart runbook evidence directly supports runtime restart or container instability."));
        }
        if (evidence.getType() == EvidenceType.RUNBOOK && containsAny(content, "runbook", "service_unavailable", "slow_response", "cpu_high_usage")) {
            matches.add(new EvidenceMatch(H_APP_ERROR, EvidenceStrength.WEAK_SUPPORT,
                    "Runbook retrieval found related incident handling guidance."));
            matches.add(new EvidenceMatch(H_DB_BOTTLENECK, EvidenceStrength.WEAK_SUPPORT,
                    "Runbook retrieval found related slow response or database guidance."));
        }
        if (evidence.getType() == EvidenceType.TOOL_ERROR) {
            matches.add(new EvidenceMatch(H_APP_ERROR, EvidenceStrength.NEUTRAL,
                    "Tool failure is kept as context but does not change the root-cause posterior."));
        }

        addContradictions(content, matches);
        return matches;
    }

    private void addContradictions(String content, List<EvidenceMatch> matches) {
        if (containsAny(content, "cpu_usage\", \"30", "cpu usage normal", "cpu normal")) {
            matches.add(new EvidenceMatch(H_CPU_BOTTLENECK, EvidenceStrength.MEDIUM_CONTRADICTION,
                    "Normal CPU evidence contradicts the CPU bottleneck hypothesis."));
        }
        if (containsAny(content, "memory_usage\", \"30", "memory normal")) {
            matches.add(new EvidenceMatch(H_MEMORY_PRESSURE, EvidenceStrength.MEDIUM_CONTRADICTION,
                    "Normal memory evidence contradicts the memory pressure hypothesis."));
        }
        if (containsAny(content, "disk_usage\", \"30", "disk normal")) {
            matches.add(new EvidenceMatch(H_DISK_PRESSURE, EvidenceStrength.MEDIUM_CONTRADICTION,
                    "Normal disk evidence contradicts the disk pressure hypothesis."));
        }
        if (containsAny(content, "no slow query", "no timeout", "pool usage 20")) {
            matches.add(new EvidenceMatch(H_DB_CONNECTION_POOL, EvidenceStrength.STRONG_CONTRADICTION,
                    "No timeout or low pool usage directly contradicts connection pool exhaustion."));
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean containsAny(String content, String... tokens) {
        for (String token : tokens) {
            if (content.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
