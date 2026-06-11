package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class QueryPreprocessService {
    private static final Logger logger = LoggerFactory.getLogger(QueryPreprocessService.class);

    private static final List<String> REWRITE_TRIGGER_KEYWORDS = List.of(
            "analyze", "analysis", "why", "root cause", "troubleshoot", "investigate",
            "compare", "difference", "workflow", "multiple", "logs", "metrics", "alert",
            "分析", "原因", "根因", "排查", "方案", "步骤", "影响", "优化",
            "对比", "区别", "结合", "多个", "链路", "日志", "指标", "告警", "历史"
    );

    @Autowired
    private ChatService chatService;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean rewriteEnabled;

    @Value("${rag.query-rewrite.on-demand-enabled:true}")
    private boolean rewriteOnDemandEnabled;

    @Value("${rag.query-rewrite.min-trigger-length:24}")
    private int rewriteMinTriggerLength;

    @Value("${rag.query-rewrite.min-similarity:0.8}")
    private double minRewriteSimilarity;

    public QueryPreprocessResult preprocess(String originalQuery) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return QueryPreprocessResult.original(originalQuery);
        }

        String normalizedQuery = normalize(originalQuery);
        if (!rewriteEnabled) {
            return QueryPreprocessResult.original(normalizedQuery);
        }

        if (rewriteOnDemandEnabled && !shouldRewrite(normalizedQuery)) {
            return new QueryPreprocessResult(
                    normalizedQuery,
                    normalizedQuery,
                    null,
                    1.0,
                    false,
                    "rewrite skipped by on-demand heuristic"
            );
        }

        try {
            String rewrittenQuery = rewriteQuery(normalizedQuery);
            if (rewrittenQuery == null || rewrittenQuery.isBlank()
                    || rewrittenQuery.equalsIgnoreCase(normalizedQuery)) {
                return QueryPreprocessResult.original(normalizedQuery);
            }

            List<Float> originalVector = embeddingService.generateQueryVector(normalizedQuery);
            List<Float> rewrittenVector = embeddingService.generateQueryVector(rewrittenQuery);
            double similarity = cosineSimilarity(originalVector, rewrittenVector);

            boolean accepted = similarity >= minRewriteSimilarity;
            String finalQuery = accepted ? rewrittenQuery : normalizedQuery;

            logger.info("Query rewrite {}, similarity={}, original={}, rewritten={}",
                    accepted ? "accepted" : "rejected",
                    String.format(Locale.ROOT, "%.4f", similarity),
                    normalizedQuery,
                    rewrittenQuery);

            return new QueryPreprocessResult(
                    normalizedQuery,
                    finalQuery,
                    rewrittenQuery,
                    similarity,
                    accepted,
                    accepted ? "rewrite accepted" : "rewrite rejected by similarity guard"
            );
        } catch (Exception e) {
            logger.warn("Query rewrite failed, fallback to original query: {}", e.getMessage());
            return QueryPreprocessResult.original(normalizedQuery);
        }
    }

    private boolean shouldRewrite(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.length() >= rewriteMinTriggerLength) {
            return true;
        }

        for (String keyword : REWRITE_TRIGGER_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }

        int separatorCount = 0;
        for (char c : normalized.toCharArray()) {
            if (c == ',' || c == ';' || c == '?' || c == '？' || c == '，' || c == '；' || c == '、') {
                separatorCount++;
            }
        }
        return separatorCount >= 2;
    }

    private String rewriteQuery(String query) {
        DashScopeChatModel chatModel = chatService.createStandardChatModel(chatService.createDashScopeApi());
        String prompt = """
                Rewrite the user question into a concise query for internal knowledge-base retrieval.
                Requirements:
                1. Preserve the original meaning.
                2. Do not add conditions that the user did not provide.
                3. Add useful synonyms or key entities only when they are implied by the question.
                4. Output only the rewritten query.

                User question:
                %s
                """.formatted(query);

        return sanitizeRewrite(chatModel.call(prompt));
    }

    private String sanitizeRewrite(String rewritten) {
        if (rewritten == null) {
            return null;
        }
        String result = rewritten.trim();
        result = result.replaceAll("(?i)^rewritten query:\\s*", "");
        result = result.replaceAll("(?i)^query:\\s*", "");
        result = result.replaceAll("^\"|\"$", "");
        result = result.replaceAll("^`+|`+$", "");
        return normalize(result);
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }

    private double cosineSimilarity(List<Float> left, List<Float> right) {
        if (left == null || right == null || left.size() != right.size() || left.isEmpty()) {
            return 0.0;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;

        for (int i = 0; i < left.size(); i++) {
            double l = left.get(i);
            double r = right.get(i);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }

        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }

        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public record QueryPreprocessResult(
            String originalQuery,
            String finalQuery,
            String rewrittenQuery,
            double similarity,
            boolean rewriteAccepted,
            String reason
    ) {
        public static QueryPreprocessResult original(String query) {
            return new QueryPreprocessResult(query, query, null, 1.0, false, "original query");
        }
    }
}
