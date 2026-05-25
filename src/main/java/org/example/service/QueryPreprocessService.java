package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 查询预处理服务。
 * 负责 query rewrite，并用向量相似度保护用户原始语义不被改偏。
 */
@Service
public class QueryPreprocessService {

    private static final Logger logger = LoggerFactory.getLogger(QueryPreprocessService.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Value("${rag.query-rewrite.enabled:true}")
    private boolean rewriteEnabled;

    @Value("${rag.query-rewrite.min-similarity:0.8}")
    private double minRewriteSimilarity;

    public QueryPreprocessResult preprocess(String originalQuery) {
        if (originalQuery == null || originalQuery.trim().isEmpty()) {
            return QueryPreprocessResult.original(originalQuery);
        }

        String normalizedQuery = originalQuery.trim();
        if (!rewriteEnabled) {
            return QueryPreprocessResult.original(normalizedQuery);
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
                    String.format("%.4f", similarity),
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

    private String rewriteQuery(String query) {
        DashScopeChatModel chatModel = chatService.createStandardChatModel(chatService.createDashScopeApi());
        String prompt = """
                请将下面的用户问题改写成更适合知识库检索的查询语句。
                要求：
                1. 保留原始语义，不要新增用户没有表达的条件。
                2. 补全同义词和关键实体。
                3. 只输出改写后的查询语句，不要解释。

                用户问题：
                %s
                """.formatted(query);

        String rewritten = chatModel.call(prompt);
        return sanitizeRewrite(rewritten);
    }

    private String sanitizeRewrite(String rewritten) {
        if (rewritten == null) {
            return null;
        }
        String result = rewritten.trim();
        result = result.replaceAll("(?i)^改写后[:：]\\s*", "");
        result = result.replaceAll("^\"|\"$", "");
        result = result.replaceAll("^`+|`+$", "");
        return result.trim();
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
