package org.example.service;

import org.example.service.QueryComplexityService.RetrievalMode;
import org.example.trace.AgentTraceService;
import org.example.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Low-cost agentic retrieval planner.
 *
 * <p>The service does not call an LLM. It plans a few deterministic follow-up
 * retrieval queries for troubleshooting questions, then merges them with the
 * first-pass hybrid results. The goal is to reduce evidence gaps before rerank
 * and Runbook GraphRAG, while keeping latency and cost bounded.
 */
@Service
public class AgenticRetrievalService {
    private final HybridRetrievalService hybridRetrievalService;
    private final AgentTraceService traceService;

    @Value("${rag.agentic-retrieval.enabled:true}")
    private boolean enabled;

    @Value("${rag.agentic-retrieval.max-subqueries:4}")
    private int maxSubqueries;

    @Value("${rag.agentic-retrieval.subquery-top-k:6}")
    private int subqueryTopK;

    @Value("${rag.agentic-retrieval.skip-when-primary-runbook-hit:true}")
    private boolean skipWhenPrimaryRunbookHit;

    public AgenticRetrievalService(
            HybridRetrievalService hybridRetrievalService,
            AgentTraceService traceService
    ) {
        this.hybridRetrievalService = hybridRetrievalService;
        this.traceService = traceService;
    }

    public ExpansionResult expand(
            String originalQuery,
            String finalQuery,
            List<VectorSearchService.SearchResult> initialResults,
            int requestedTopK,
            RetrievalMode retrievalMode
    ) {
        if (!enabled) {
            return ExpansionResult.skipped(initialResults, "disabled");
        }
        String query = normalize(String.join(" ", safe(originalQuery), safe(finalQuery)));
        String primaryRunbook = primaryRunbook(query);
        if (skipWhenPrimaryRunbookHit && !primaryRunbook.isBlank() && hasSource(initialResults, primaryRunbook)) {
            return ExpansionResult.skipped(initialResults, "primary_runbook_hit:" + primaryRunbook);
        }
        List<String> subqueries = planSubqueries(originalQuery, finalQuery);
        if (subqueries.isEmpty()) {
            return ExpansionResult.skipped(initialResults, "no_follow_up_needed");
        }

        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        if (initialResults != null) {
            for (VectorSearchService.SearchResult result : initialResults) {
                merged.putIfAbsent(result.getId(), result);
            }
        }

        List<SubqueryTrace> traces = new ArrayList<>();
        int addedCount = 0;
        int actualTopK = Math.max(1, Math.min(Math.max(requestedTopK, subqueryTopK), requestedTopK * 2));
        try (TraceSpan span = traceService.startSpan("rag", "agentic_retrieval", Map.of(
                "subqueryCount", subqueries.size(),
                "subqueryTopK", actualTopK
        ))) {
            for (String subquery : subqueries) {
                List<VectorSearchService.SearchResult> results = hybridRetrievalService.retrieve(
                        subquery,
                        finalQuery,
                        actualTopK,
                        retrievalMode
                );
                int before = merged.size();
                for (VectorSearchService.SearchResult result : results) {
                    if (merged.putIfAbsent(result.getId(), markAgentic(result, subquery)) == null) {
                        addedCount++;
                    }
                }
                traces.add(new SubqueryTrace(subquery, results.size(), merged.size() - before));
            }
            span.success(Map.of(
                    "addedCount", addedCount,
                    "mergedCount", merged.size()
            ));
        }

        return new ExpansionResult(List.copyOf(merged.values()), true, subqueries, traces, addedCount, "expanded");
    }

    private VectorSearchService.SearchResult markAgentic(VectorSearchService.SearchResult result, String subquery) {
        if (result.getRetrievalMode() == null || result.getRetrievalMode().isBlank()) {
            result.setRetrievalMode("AGENTIC");
        } else if (!result.getRetrievalMode().contains("AGENTIC")) {
            result.setRetrievalMode(result.getRetrievalMode() + "+AGENTIC");
        }
        return result;
    }

    private List<String> planSubqueries(String originalQuery, String finalQuery) {
        String query = normalize(String.join(" ", safe(originalQuery), safe(finalQuery)));
        if (!isTroubleshootingQuery(query)) {
            return List.of();
        }

        List<String> planned = new ArrayList<>();
        if (query.contains("mq") || query.contains("backlog") || query.contains("consumer")) {
            planned.add("MQ backlog downstream dependency slow database Redis HTTP timeout evidence actions backpressure");
            planned.add("MQ backlog consumer capacity insufficient lag retry queue evidence actions");
            planned.add("MQ backlog poison message schema mismatch evidence actions");
            planned.add("MQ backlog safe operations verification");
        } else if (query.contains("redis") || query.contains("cache")) {
            planned.add("Redis timeout hot key evidence actions");
            planned.add("Redis timeout big key slow command slowlog evidence actions");
            planned.add("Redis timeout cache avalanche TTL cache hit rate evidence actions");
            planned.add("Redis timeout safe operations verification");
        } else if (query.contains("database") || query.contains("db") || query.contains("sql") || query.contains("connection")) {
            planned.add("Database connection pool too small active pending idle evidence actions");
            planned.add("Database slow SQL lock contention lock wait evidence actions");
            planned.add("Database connection leak HikariPool evidence actions");
            planned.add("Database connection pool safe operations verification");
        } else if (query.contains("pod") || query.contains("kubernetes") || query.contains("crashloopbackoff") || query.contains("oomkilled")) {
            planned.add("Pod restart OOMKilled CrashLoopBackOff evidence actions");
            planned.add("Pod restart probe misconfiguration liveness readiness initial delay evidence actions");
            planned.add("Pod restart bad deployment invalid config evidence actions");
            planned.add("Pod restart safe operations verification");
        } else {
            planned.add(finalQuery + " evidence root cause actions");
            planned.add(finalQuery + " safe operations verification");
        }

        Set<String> deduped = new LinkedHashSet<>();
        for (String item : planned) {
            String normalized = normalize(item);
            if (!normalized.isBlank() && !equivalent(normalized, finalQuery)) {
                deduped.add(normalized);
            }
            if (deduped.size() >= Math.max(1, maxSubqueries)) {
                break;
            }
        }
        return List.copyOf(deduped);
    }

    private String primaryRunbook(String query) {
        if (query.contains("mq") || query.contains("backlog") || query.contains("consumer")) {
            return "mq_backlog.md";
        }
        if (query.contains("redis") || query.contains("cache")) {
            return "redis_timeout.md";
        }
        if (query.contains("database") || query.contains("db") || query.contains("sql") || query.contains("connection")) {
            return "db_connection_pool.md";
        }
        if (query.contains("pod") || query.contains("kubernetes") || query.contains("crashloopbackoff") || query.contains("oomkilled")) {
            return "pod_restart.md";
        }
        return "";
    }

    private boolean hasSource(List<VectorSearchService.SearchResult> results, String sourceFile) {
        if (results == null || results.isEmpty() || sourceFile == null || sourceFile.isBlank()) {
            return false;
        }
        for (VectorSearchService.SearchResult result : results) {
            String metadata = result.getMetadata();
            if (metadata != null && metadata.contains("\"_file_name\":\"" + sourceFile + "\"")) {
                return true;
            }
        }
        return false;
    }

    private boolean isTroubleshootingQuery(String query) {
        return query.contains("troubleshoot")
                || query.contains("investigate")
                || query.contains("analysis")
                || query.contains("analyze")
                || query.contains("root cause")
                || query.contains("timeout")
                || query.contains("latency")
                || query.contains("slow")
                || query.contains("error")
                || query.contains("故障")
                || query.contains("排查")
                || query.contains("分析")
                || query.contains("根因")
                || query.contains("变慢");
    }

    private boolean equivalent(String left, String right) {
        return normalize(left).equalsIgnoreCase(normalize(right));
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ExpansionResult(
            List<VectorSearchService.SearchResult> results,
            boolean expanded,
            List<String> subqueries,
            List<SubqueryTrace> traces,
            int addedCount,
            String reason
    ) {
        static ExpansionResult skipped(List<VectorSearchService.SearchResult> results, String reason) {
            return new ExpansionResult(
                    results == null ? List.of() : results,
                    false,
                    List.of(),
                    List.of(),
                    0,
                    reason
            );
        }
    }

    public record SubqueryTrace(
            String query,
            int resultCount,
            int addedCount
    ) {
    }
}
