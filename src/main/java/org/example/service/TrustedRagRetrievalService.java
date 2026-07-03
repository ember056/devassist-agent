package org.example.service;

import lombok.Getter;
import org.example.trace.AgentTraceService;
import org.example.trace.TraceSpan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class TrustedRagRetrievalService {
    @Autowired
    private QueryPreprocessService queryPreprocessService;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryComplexityService queryComplexityService;

    @Autowired
    private RerankService rerankService;

    @Autowired
    private AgenticRetrievalService agenticRetrievalService;

    @Autowired
    private AgentTraceService traceService;

    @Value("${rag.retrieval.cache.enabled:true}")
    private boolean retrievalCacheEnabled;

    @Value("${rag.retrieval.cache.max-size:2000}")
    private int retrievalCacheMaxSize;

    @Value("${rag.retrieval.cache.ttl-seconds:300}")
    private long retrievalCacheTtlSeconds;

    private final Map<String, CacheEntry<TrustedRagResult>> retrievalCache = new ConcurrentHashMap<>();

    public TrustedRagResult retrieve(String question, int topK) {
        String cacheKey = cacheKey(question, topK);
        if (retrievalCacheEnabled) {
            CacheEntry<TrustedRagResult> cached = retrievalCache.get(cacheKey);
            if (cached != null && !cached.expired()) {
                traceService.event("rag", "retrieval_cache_hit", Map.of(
                        "topK", topK,
                        "queryLength", question == null ? 0 : question.length()
                ));
                return copyResult(cached.value());
            }
        }

        QueryPreprocessService.QueryPreprocessResult preprocessResult;
        try (TraceSpan span = traceService.startSpan("rag", "query_preprocess", Map.of("topK", topK))) {
            preprocessResult = queryPreprocessService.preprocess(question);
            span.success(Map.of(
                    "rewriteAccepted", preprocessResult.rewriteAccepted(),
                    "rewriteSimilarity", preprocessResult.similarity(),
                    "reason", preprocessResult.reason()
            ));
        }

        QueryComplexityService.QueryRoute route =
                queryComplexityService.route(preprocessResult.finalQuery());
        traceService.event("rag", "query_route", Map.of(
                "complexity", route.getComplexity().name(),
                "retrievalMode", route.getRetrievalMode().name()
        ));

        List<VectorSearchService.SearchResult> rawResults;
        try (TraceSpan span = traceService.startSpan("rag", "hybrid_retrieve", Map.of(
                "retrievalMode", route.getRetrievalMode().name(),
                "topK", topK
        ))) {
            rawResults = hybridRetrievalService.retrieve(
                    preprocessResult.finalQuery(),
                    preprocessResult.originalQuery(),
                    topK,
                    route.getRetrievalMode()
            );
            span.success(Map.of("rawResultCount", rawResults.size()));
        }

        AgenticRetrievalService.ExpansionResult agenticExpansion =
                agenticRetrievalService.expand(
                        preprocessResult.originalQuery(),
                        preprocessResult.finalQuery(),
                        rawResults,
                        topK,
                        route.getRetrievalMode()
                );
        rawResults = agenticExpansion.results();
        traceService.event("rag", "agentic_retrieval_decision", Map.of(
                "expanded", agenticExpansion.expanded(),
                "addedCount", agenticExpansion.addedCount(),
                "subqueryCount", agenticExpansion.subqueries().size(),
                "reason", agenticExpansion.reason()
        ));

        String rerankQuery = rerankQuery(preprocessResult);
        boolean rerankApplied = route.complex()
                && rerankService.shouldRerank(rerankQuery, rawResults);
        List<VectorSearchService.SearchResult> finalResults =
                rerankApplied
                        ? rerankService.rerank(rerankQuery, rawResults)
                        : rawResults;
        finalResults = finalResults.stream()
                .limit(topK)
                .collect(Collectors.toList());
        traceService.event("rag", "rerank_decision", Map.of(
                "rerankApplied", rerankApplied,
                "finalResultCount", finalResults.size()
        ));
        assignSourceIndexes(finalResults);

        TrustedRagResult result = new TrustedRagResult(preprocessResult, finalResults, rerankApplied, route, agenticExpansion);
        if (retrievalCacheEnabled) {
            putCache(cacheKey, result);
        }
        return copyResult(result);
    }

    private void assignSourceIndexes(List<VectorSearchService.SearchResult> results) {
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setSourceIndex(i + 1);
        }
    }

    private String rerankQuery(QueryPreprocessService.QueryPreprocessResult preprocessResult) {
        if (preprocessResult == null) {
            return "";
        }
        String finalQuery = preprocessResult.finalQuery() == null ? "" : preprocessResult.finalQuery();
        String originalQuery = preprocessResult.originalQuery() == null ? "" : preprocessResult.originalQuery();
        if (originalQuery.isBlank() || originalQuery.equalsIgnoreCase(finalQuery)) {
            return finalQuery;
        }
        return finalQuery + " " + originalQuery;
    }

    private void putCache(String key, TrustedRagResult result) {
        if (retrievalCacheMaxSize > 0 && retrievalCache.size() >= retrievalCacheMaxSize) {
            retrievalCache.clear();
        }
        retrievalCache.put(key, new CacheEntry<>(copyResult(result), retrievalCacheTtlSeconds));
    }

    private String cacheKey(String question, int topK) {
        String normalized = question == null ? "" : question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return normalized + "|topK=" + topK;
    }

    private TrustedRagResult copyResult(TrustedRagResult result) {
        List<VectorSearchService.SearchResult> copied = new ArrayList<>();
        for (VectorSearchService.SearchResult item : result.getResults()) {
            copied.add(copySearchResult(item));
        }
        return new TrustedRagResult(
                result.getPreprocess(),
                copied,
                result.isRerankApplied(),
                result.getRoute(),
                result.getAgenticExpansion()
        );
    }

    private VectorSearchService.SearchResult copySearchResult(VectorSearchService.SearchResult item) {
        VectorSearchService.SearchResult copy = new VectorSearchService.SearchResult();
        copy.setId(item.getId());
        copy.setContent(item.getContent());
        copy.setScore(item.getScore());
        copy.setMetadata(item.getMetadata());
        copy.setRerankScore(item.getRerankScore());
        copy.setBm25Score(item.getBm25Score());
        copy.setHybridScore(item.getHybridScore());
        copy.setRetrievalMode(item.getRetrievalMode());
        copy.setSourceIndex(item.getSourceIndex());
        return copy;
    }

    private static class CacheEntry<T> {
        private final T value;
        private final long expiresAtMillis;

        CacheEntry(T value, long ttlSeconds) {
            this.value = value;
            this.expiresAtMillis = System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000;
        }

        T value() {
            return value;
        }

        boolean expired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    @Getter
    public static class TrustedRagResult {
        private final QueryPreprocessService.QueryPreprocessResult preprocess;
        private final List<VectorSearchService.SearchResult> results;
        private final boolean rerankApplied;
        private final QueryComplexityService.QueryRoute route;
        private final AgenticRetrievalService.ExpansionResult agenticExpansion;

        public TrustedRagResult(
                QueryPreprocessService.QueryPreprocessResult preprocess,
                List<VectorSearchService.SearchResult> results,
                boolean rerankApplied,
                QueryComplexityService.QueryRoute route,
                AgenticRetrievalService.ExpansionResult agenticExpansion
        ) {
            this.preprocess = preprocess;
            this.results = results;
            this.rerankApplied = rerankApplied;
            this.route = route;
            this.agenticExpansion = agenticExpansion;
        }
    }
}
