package org.example.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 轻量级重排序服务。
 * 当前先用策略路由 + 词项覆盖度做本地 rerank，后续可替换为专门的 rerank 模型。
 */
@Service
public class RerankService {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "is", "are", "to", "of", "in", "on",
            "怎么", "如何", "为什么", "什么", "一下", "请", "帮我", "查询", "分析"
    );

    @Value("${rag.rerank.enabled:true}")
    private boolean rerankEnabled;

    @Value("${rag.rerank.min-query-length:12}")
    private int minQueryLength;

    @Value("${rag.rerank.close-score-delta:0.2}")
    private double closeScoreDelta;

    public List<VectorSearchService.SearchResult> rerankIfNeeded(
            String query,
            List<VectorSearchService.SearchResult> results
    ) {
        if (!shouldRerank(query, results)) {
            return results;
        }

        return rerank(query, results);
    }

    public List<VectorSearchService.SearchResult> rerank(
            String query,
            List<VectorSearchService.SearchResult> results
    ) {
        return results.stream()
                .peek(result -> result.setRerankScore(calculateRerankScore(query, result)))
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getRerankScore).reversed())
                .collect(Collectors.toList());
    }

    public boolean shouldRerank(String query, List<VectorSearchService.SearchResult> results) {
        if (!rerankEnabled || query == null || results == null || results.size() < 2) {
            return false;
        }

        if (query.length() >= minQueryLength) {
            return true;
        }

        float best = results.get(0).getScore();
        float second = results.get(1).getScore();
        return Math.abs(best - second) <= closeScoreDelta;
    }

    private double calculateRerankScore(String query, VectorSearchService.SearchResult result) {
        Set<String> queryTerms = tokenize(query);
        Set<String> contentTerms = tokenize((result.getContent() == null ? "" : result.getContent())
                + " "
                + (result.getMetadata() == null ? "" : result.getMetadata()));

        if (queryTerms.isEmpty() || contentTerms.isEmpty()) {
            return normalizeVectorScore(result.getScore()) + sourceIntentBoost(query, result);
        }

        long matched = queryTerms.stream().filter(contentTerms::contains).count();
        double lexicalScore = matched / (double) queryTerms.size();

        return lexicalScore * 0.65 + normalizeVectorScore(result.getScore()) * 0.25 + sourceIntentBoost(query, result);
    }

    private double sourceIntentBoost(String query, VectorSearchService.SearchResult result) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String metadata = result.getMetadata() == null ? "" : result.getMetadata().toLowerCase(Locale.ROOT);
        double boost = 0.0;

        if (containsAny(normalizedQuery, "redis", "缓存", "cache", "timeout")
                && metadata.contains("redis_timeout")) {
            boost += 0.35;
        }
        if (containsAny(normalizedQuery, "connection pool", "连接池", "database query timeout", "数据库", "db")
                && metadata.contains("db_connection_pool")) {
            boost += 0.35;
        }
        if (containsAny(normalizedQuery, "consumer lag", "mq", "消息", "队列", "backlog")
                && metadata.contains("mq_backlog")) {
            boost += 0.35;
        }
        if (containsAny(normalizedQuery, "pod", "restart", "oom", "oomkilled", "crashloopbackoff", "重启")
                && metadata.contains("pod_restart")) {
            boost += 0.35;
        }
        return boost;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private double normalizeVectorScore(float l2Score) {
        return 1.0 / (1.0 + Math.max(0.0, l2Score));
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        return List.of(text.toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{IsHan}\\p{Alnum}]+", " ")
                        .trim()
                        .split("\\s+"))
                .stream()
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toSet());
    }
}
