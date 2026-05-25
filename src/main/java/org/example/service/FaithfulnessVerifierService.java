package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 答案忠实度检查。
 * 优先用 VerifierAgent 判断答案是否忠实于检索上下文，失败时回退到本地规则校验。
 */
@Service
public class FaithfulnessVerifierService {

    private static final Logger logger = LoggerFactory.getLogger(FaithfulnessVerifierService.class);

    @Autowired
    private ChatService chatService;

    @Value("${rag.faithfulness.enabled:true}")
    private boolean enabled;

    @Value("${rag.faithfulness.verifier-agent-enabled:true}")
    private boolean verifierAgentEnabled;

    @Value("${rag.faithfulness.min-coverage:0.2}")
    private double minCoverage;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public VerificationResult verify(String answer, List<VectorSearchService.SearchResult> sources) {
        if (!enabled) {
            return VerificationResult.skipped();
        }
        if (answer == null || answer.isBlank() || sources == null || sources.isEmpty()) {
            return new VerificationResult(false, 0.0, "answer or sources are empty", "RULE", List.of());
        }

        if (verifierAgentEnabled) {
            try {
                VerificationResult agentResult = verifyWithAgent(answer, sources);
                if (agentResult != null) {
                    return agentResult;
                }
            } catch (Exception e) {
                logger.warn("VerifierAgent failed, fallback to rule verifier: {}", e.getMessage());
            }
        }

        return verifyWithRules(answer, sources);
    }

    private VerificationResult verifyWithAgent(String answer, List<VectorSearchService.SearchResult> sources)
            throws Exception {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createChatModel(dashScopeApi, 0.1, 1200, 0.8);

        ReactAgent verifierAgent = ReactAgent.builder()
                .name("faithfulness_verifier")
                .model(chatModel)
                .systemPrompt("""
                        你是 RAG 答案忠实度校验 Agent。
                        你的任务是检查“待校验答案”是否严格由“参考资料”支持。
                        只允许根据参考资料判断，不要使用外部知识。
                        必须输出 JSON，格式如下：
                        {
                          "passed": true,
                          "coverage": 0.92,
                          "reason": "答案中的主要结论均可由参考资料支持",
                          "unsupportedClaims": []
                        }
                        如果答案包含参考资料没有支持的结论，passed=false，并把问题点写入 unsupportedClaims。
                        """)
                .build();

        String response = verifierAgent.call(buildVerificationPrompt(answer, sources)).getText();
        return parseAgentVerification(response);
    }

    private String buildVerificationPrompt(String answer, List<VectorSearchService.SearchResult> sources) {
        StringBuilder context = new StringBuilder();
        for (VectorSearchService.SearchResult source : sources) {
            int sourceIndex = source.getSourceIndex() == null ? 0 : source.getSourceIndex();
            context.append("【参考资料 ").append(sourceIndex).append("】\n")
                    .append(source.getContent()).append("\n\n");
        }

        return String.format("""
                参考资料：
                %s

                待校验答案：
                %s

                请判断答案是否忠实于参考资料，并只输出 JSON。
                """, context, answer);
    }

    private VerificationResult parseAgentVerification(String response) throws Exception {
        String json = extractJson(response);
        JsonNode root = objectMapper.readTree(json);

        boolean passed = root.path("passed").asBoolean(false);
        double coverage = root.path("coverage").asDouble(passed ? 1.0 : 0.0);
        String reason = root.path("reason").asText(passed
                ? "VerifierAgent passed"
                : "VerifierAgent found unsupported claims");

        List<String> unsupportedClaims = new ArrayList<>();
        JsonNode claimsNode = root.path("unsupportedClaims");
        if (claimsNode.isArray()) {
            claimsNode.forEach(node -> unsupportedClaims.add(node.asText()));
        }

        return new VerificationResult(passed, coverage, reason, "VERIFIER_AGENT", unsupportedClaims);
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private VerificationResult verifyWithRules(String answer, List<VectorSearchService.SearchResult> sources) {
        Set<String> answerTerms = tokenize(answer);
        Set<String> sourceTerms = sources.stream()
                .flatMap(source -> tokenize(source.getContent()).stream())
                .collect(Collectors.toSet());

        if (answerTerms.isEmpty() || sourceTerms.isEmpty()) {
            return new VerificationResult(false, 0.0, "not enough text for verification", "RULE", List.of());
        }

        long supportedTerms = answerTerms.stream().filter(sourceTerms::contains).count();
        double coverage = supportedTerms / (double) answerTerms.size();
        boolean passed = coverage >= minCoverage || answer.contains("参考资料");

        return new VerificationResult(
                passed,
                coverage,
                passed ? "answer is supported by retrieved context" : "answer has low source coverage",
                "RULE",
                List.of()
        );
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
                .collect(Collectors.toSet());
    }

    public record VerificationResult(
            boolean passed,
            double coverage,
            String reason,
            String verifier,
            List<String> unsupportedClaims
    ) {
        public static VerificationResult skipped() {
            return new VerificationResult(true, 1.0, "faithfulness verification disabled", "SKIPPED", List.of());
        }
    }
}
