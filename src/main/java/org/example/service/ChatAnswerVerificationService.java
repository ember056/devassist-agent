package org.example.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 普通聊天答案后置校验。
 * 只对知识库、文档、排障类问题触发 RAG sources 检索和 VerifierAgent 校验。
 */
@Service
public class ChatAnswerVerificationService {

    private static final Set<String> RAG_INTENT_KEYWORDS = Set.of(
            "文档", "知识库", "流程", "最佳实践", "手册", "步骤", "排查", "故障",
            "告警", "根因", "处理", "修复", "oncall", "runbook", "document",
            "knowledge", "troubleshoot", "incident", "alert"
    );

    @Autowired
    private TrustedRagRetrievalService trustedRagRetrievalService;

    @Autowired
    private FaithfulnessVerifierService faithfulnessVerifierService;

    @Value("${rag.chat-verification.enabled:true}")
    private boolean enabled;

    @Value("${rag.chat-verification.top-k:3}")
    private int topK;

    public String verifyIfNeeded(String question, String answer) {
        if (!enabled || question == null || answer == null || !shouldVerify(question)) {
            return answer;
        }

        try {
            TrustedRagRetrievalService.TrustedRagResult trustedRagResult =
                    trustedRagRetrievalService.retrieve(question, topK);
            List<VectorSearchService.SearchResult> sources = trustedRagResult.getResults();

            if (sources.isEmpty()) {
                return answer;
            }

            FaithfulnessVerifierService.VerificationResult verificationResult =
                    faithfulnessVerifierService.verify(answer, sources);

            String verificationBlock = buildVerificationBlock(trustedRagResult, verificationResult);
            return answer + verificationBlock;
        } catch (Exception e) {
            return answer + "\n\n> 可信 RAG 后置校验未完成：" + e.getMessage();
        }
    }

    private boolean shouldVerify(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return RAG_INTENT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private String buildVerificationBlock(
            TrustedRagRetrievalService.TrustedRagResult trustedRagResult,
            FaithfulnessVerifierService.VerificationResult verificationResult
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n\n---\n");
        builder.append("### 可信 RAG 校验\n\n");
        builder.append("- 原始问题：").append(trustedRagResult.getPreprocess().originalQuery()).append("\n");
        builder.append("- 实际检索问题：").append(trustedRagResult.getPreprocess().finalQuery()).append("\n");
        builder.append("- Query Rewrite 采用：")
                .append(trustedRagResult.getPreprocess().rewriteAccepted() ? "是" : "否")
                .append("\n");
        builder.append("- Rewrite 相似度：")
                .append(String.format("%.4f", trustedRagResult.getPreprocess().similarity()))
                .append("\n");
        builder.append("- Query 复杂度：")
                .append(trustedRagResult.getRoute().getComplexity().name())
                .append("\n");
        builder.append("- 召回模式：")
                .append(trustedRagResult.getRoute().getRetrievalMode().name())
                .append("\n");
        builder.append("- Rerank 触发：")
                .append(trustedRagResult.isRerankApplied() ? "是" : "否")
                .append("\n");
        builder.append("- Faithfulness：")
                .append(verificationResult.passed() ? "通过" : "未通过")
                .append("（")
                .append(verificationResult.verifier())
                .append(", coverage=")
                .append(String.format("%.4f", verificationResult.coverage()))
                .append("）\n");
        builder.append("- 校验说明：").append(verificationResult.reason()).append("\n");

        if (!verificationResult.unsupportedClaims().isEmpty()) {
            builder.append("- 未被资料支持的表述：")
                    .append(String.join("；", verificationResult.unsupportedClaims()))
                    .append("\n");
        }

        builder.append("\n参考来源：\n");
        for (VectorSearchService.SearchResult source : trustedRagResult.getResults()) {
            builder.append("- 【参考资料 ")
                    .append(source.getSourceIndex())
                    .append("】score=")
                    .append(source.getScore());
            if (source.getBm25Score() != null) {
                builder.append(", bm25Score=")
                        .append(String.format("%.4f", source.getBm25Score()));
            }
            if (source.getHybridScore() != null) {
                builder.append(", hybridScore=")
                        .append(String.format("%.4f", source.getHybridScore()));
            }
            if (source.getRerankScore() != null) {
                builder.append(", rerankScore=")
                        .append(String.format("%.4f", source.getRerankScore()));
            }
            if (source.getRetrievalMode() != null) {
                builder.append(", retrievalMode=").append(source.getRetrievalMode());
            }
            if (source.getMetadata() != null) {
                builder.append(", metadata=").append(source.getMetadata());
            }
            builder.append("\n");
        }

        return builder.toString();
    }
}
