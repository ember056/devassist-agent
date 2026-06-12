package org.example.service.aiops;

import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.trace.AgentTraceService;
import org.example.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AiOpsEvidenceCollectorService {
    private final QueryMetricsTools queryMetricsTools;
    private final InternalDocsTools internalDocsTools;
    private final QueryLogsTools queryLogsTools;
    private final AgentTraceService traceService;
    private final AtomicInteger sequence = new AtomicInteger();

    @Autowired
    public AiOpsEvidenceCollectorService(
            QueryMetricsTools queryMetricsTools,
            InternalDocsTools internalDocsTools,
            ObjectProvider<QueryLogsTools> queryLogsToolsProvider,
            AgentTraceService traceService
    ) {
        this.queryMetricsTools = queryMetricsTools;
        this.internalDocsTools = internalDocsTools;
        this.queryLogsTools = queryLogsToolsProvider.getIfAvailable();
        this.traceService = traceService;
    }

    public List<EvidenceNode> collect(String incidentRequest) {
        List<EvidenceNode> evidence = new ArrayList<>();

        evidence.add(new EvidenceNode(
                nextId(),
                EvidenceType.ALERT,
                "user_request",
                "Incident request",
                safe(incidentRequest)
        ));

        evidence.add(callTool(
                EvidenceType.ALERT,
                "queryPrometheusAlerts",
                "Active Prometheus alerts",
                queryMetricsTools::queryPrometheusAlerts
        ));

        if (queryLogsTools != null) {
            evidence.add(callTool(
                    EvidenceType.METRIC_LOG,
                    "queryLogs/system-metrics",
                    "System resource metrics",
                    () -> queryLogsTools.queryLogs("ap-guangzhou", "system-metrics", "cpu_usage:>80 OR memory_usage:>85 OR disk_usage:>90", 20)
            ));
            evidence.add(callTool(
                    EvidenceType.APPLICATION_LOG,
                    "queryLogs/application-logs",
                    "Application error and dependency logs",
                    () -> queryLogsTools.queryLogs("ap-guangzhou", "application-logs", "level:ERROR OR http_status:500 OR downstream OR database OR redis OR timeout", 20)
            ));
            evidence.add(callTool(
                    EvidenceType.DATABASE_LOG,
                    "queryLogs/database-slow-query",
                    "Database slow query logs",
                    () -> queryLogsTools.queryLogs("ap-guangzhou", "database-slow-query", "query_time:>2 OR timeout", 20)
            ));
            evidence.add(callTool(
                    EvidenceType.SYSTEM_EVENT,
                    "queryLogs/system-events",
                    "Runtime restart and OOM events",
                    () -> queryLogsTools.queryLogs("ap-guangzhou", "system-events", "restart OR crash OR oom_kill OR OOMKilled", 20)
            ));
        } else {
            evidence.add(new EvidenceNode(
                    nextId(),
                    EvidenceType.TOOL_ERROR,
                    "queryLogs",
                    "Log tool unavailable",
                    "QueryLogsTools is not available; skip log evidence collection."
            ));
        }

        evidence.add(callTool(
                EvidenceType.RUNBOOK,
                "queryInternalDocs",
                "Matched internal runbook",
                () -> internalDocsTools.queryInternalDocs(buildRunbookQuery(incidentRequest))
        ));

        return evidence;
    }

    private EvidenceNode callTool(EvidenceType type, String source, String summary, ToolCall call) {
        try (TraceSpan span = traceService.startSpan("tool", source, Map.of("evidenceType", type.name()))) {
            String content = call.execute();
            span.success(Map.of("contentLength", content == null ? 0 : content.length()));
            return new EvidenceNode(nextId(), type, source, summary, content);
        } catch (Exception e) {
            traceService.event("tool", source + "_failed", Map.of("error", e.getMessage()));
            return new EvidenceNode(
                    nextId(),
                    EvidenceType.TOOL_ERROR,
                    source,
                    summary + " failed",
                    e.getMessage()
            );
        }
    }

    private String buildRunbookQuery(String incidentRequest) {
        String request = safe(incidentRequest);
        if (request.isBlank()) {
            return "service unavailable high cpu memory disk slow response runbook";
        }
        return request + " service unavailable slow response high cpu database timeout runbook";
    }

    private String nextId() {
        return "E" + sequence.incrementAndGet();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private interface ToolCall {
        String execute();
    }
}
