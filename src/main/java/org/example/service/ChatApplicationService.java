package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import org.example.trace.AgentTraceService;
import org.example.trace.TraceContext;
import org.example.trace.TraceSpan;
import org.example.trace.TraceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChatApplicationService {
    private static final Logger logger = LoggerFactory.getLogger(ChatApplicationService.class);
    private static final String SIMPLE_GREETING_REPLY =
            "你好，我是 DevAssist Agent，可以帮你检索运维文档、分析告警日志和生成故障诊断建议。";
    private static final String IDENTITY_REPLY =
            "我是 DevAssist Agent，一个面向运维场景的智能助手，重点支持 RAG 知识库检索、告警分析、日志排查和故障诊断建议。";
    private static final String THANKS_REPLY =
            "不客气。需要排查告警、查询运维文档或分析日志时，直接把现象发给我就行。";
    private static final Set<String> SIMPLE_GREETINGS = Set.of(
            "你好", "您好", "嗨", "哈喽", "hello", "hi", "hey", "在吗", "在不在"
    );
    private static final Set<String> SIMPLE_THANKS = Set.of(
            "谢谢", "多谢", "感谢", "thanks", "thankyou"
    );
    private static final Set<String> SIMPLE_IDENTITY_QUESTIONS = Set.of(
            "你是谁", "你能做什么", "你是什么", "介绍一下你", "介绍下你"
    );

    private final ChatService chatService;
    private final ChatAnswerVerificationService chatAnswerVerificationService;
    private final ChatMemoryService chatMemoryService;
    private final AgentTraceService traceService;
    private final TrustedRagRetrievalService trustedRagRetrievalService;
    private final FaithfulnessVerifierService faithfulnessVerifierService;

    @Value("${rag.top-k:3}")
    private int ragTopK;

    public ChatApplicationService(
            ChatService chatService,
            ChatAnswerVerificationService chatAnswerVerificationService,
            ChatMemoryService chatMemoryService,
            AgentTraceService traceService,
            TrustedRagRetrievalService trustedRagRetrievalService,
            FaithfulnessVerifierService faithfulnessVerifierService
    ) {
        this.chatService = chatService;
        this.chatAnswerVerificationService = chatAnswerVerificationService;
        this.chatMemoryService = chatMemoryService;
        this.traceService = traceService;
        this.trustedRagRetrievalService = trustedRagRetrievalService;
        this.faithfulnessVerifierService = faithfulnessVerifierService;
    }

    public ChatResult chat(String requestedSessionId, String question) throws Exception {
        String traceId = traceService.startTrace("chat", question);
        try (TraceContext.Scope ignored = traceService.bind(traceId)) {
            String sessionId = chatMemoryService.resolveSessionId(requestedSessionId);
            traceService.event("chat", "session_resolved", Map.of("sessionId", sessionId));

            String fastReply = buildFastReplyIfSimple(question);
            if (fastReply != null) {
                traceService.event("chat", "fast_path", Map.of("reason", "simple_input"));
                chatMemoryService.addMessage(sessionId, question, fastReply);
                traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
                return new ChatResult(sessionId, fastReply, traceId);
            }

            if (shouldUseGroundedRag(question)) {
                String groundedAnswer;
                try (TraceSpan span = traceService.startSpan("chat", "grounded_rag_answer", Map.of("topK", ragTopK))) {
                    groundedAnswer = buildGroundedRagAnswer(question);
                    span.success(Map.of("answerLength", groundedAnswer.length()));
                }
                chatMemoryService.addMessage(sessionId, question, groundedAnswer);
                traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
                return new ChatResult(sessionId, groundedAnswer, traceId);
            }

            ReactAgent agent = createAgent(sessionId, question);

            String fullAnswer;
            try (TraceSpan span = traceService.startSpan("chat", "agent_execute", Map.of("questionLength", question.length()))) {
                fullAnswer = chatService.executeChat(agent, question);
                span.success(Map.of("answerLength", fullAnswer == null ? 0 : fullAnswer.length()));
            }

            try (TraceSpan span = traceService.startSpan("chat", "answer_verify", null)) {
                fullAnswer = chatAnswerVerificationService.verifyIfNeeded(question, fullAnswer);
                span.success(Map.of("verifiedAnswerLength", fullAnswer == null ? 0 : fullAnswer.length()));
            }

            chatMemoryService.addMessage(sessionId, question, fullAnswer);
            traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
            return new ChatResult(sessionId, fullAnswer, traceId);
        } catch (Exception e) {
            traceService.finishTrace(traceId, TraceStatus.ERROR, e.getMessage());
            throw e;
        }
    }

    public void streamChat(String requestedSessionId, String question, StreamHandler handler) throws Exception {
        String traceId = traceService.startTrace("chat_stream", question);
        try (TraceContext.Scope ignored = traceService.bind(traceId)) {
            String sessionId = chatMemoryService.resolveSessionId(requestedSessionId);
            handler.onSession(sessionId);
            handler.onTrace(traceId);
            traceService.event("chat_stream", "session_resolved", Map.of("sessionId", sessionId));

            String fastReply = buildFastReplyIfSimple(question);
            if (fastReply != null) {
                traceService.event("chat_stream", "fast_path", Map.of("reason", "simple_input"));
                handler.onContent(fastReply);
                chatMemoryService.addMessage(sessionId, question, fastReply);
                traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
                handler.onDone();
                return;
            }

            if (shouldUseGroundedRag(question)) {
                String groundedAnswer;
                try (TraceSpan span = traceService.startSpan("chat_stream", "grounded_rag_answer", Map.of("topK", ragTopK))) {
                    groundedAnswer = buildGroundedRagAnswer(question);
                    span.success(Map.of("answerLength", groundedAnswer.length()));
                }
                handler.onContent(groundedAnswer);
                chatMemoryService.addMessage(sessionId, question, groundedAnswer);
                traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
                handler.onDone();
                return;
            }

            ReactAgent agent = createAgent(sessionId, question);
            StringBuilder fullAnswerBuilder = new StringBuilder();
            Flux<NodeOutput> stream = agent.stream(question);

            stream.subscribe(
                    output -> handleStreamOutput(output, fullAnswerBuilder, handler),
                    error -> handleStreamError(error, handler, traceId),
                    () -> handleStreamComplete(sessionId, question, fullAnswerBuilder, handler, traceId)
            );
        }
    }

    public boolean clearHistory(String sessionId) {
        return chatMemoryService.clearHistory(sessionId);
    }

    public ChatMemoryService.SessionSummary getSessionSummary(String sessionId) {
        return chatMemoryService.getSessionSummary(sessionId);
    }

    private ReactAgent createAgent(String sessionId, String question) {
        List<Map<String, String>> history;
        try (TraceSpan span = traceService.startSpan("chat", "load_memory", Map.of("sessionId", sessionId))) {
            history = chatMemoryService.getHistory(sessionId);
            List<Map<String, String>> semanticMemories = chatMemoryService.getRelevantSemanticMemories(sessionId, question);
            history.addAll(semanticMemories);
            span.success(Map.of("historyItems", history.size(), "semanticItems", semanticMemories.size()));
        }

        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        chatService.logAvailableTools();

        String systemPrompt = chatService.buildSystemPrompt(history);
        traceService.event("chat", "agent_created", Map.of("systemPromptLength", systemPrompt.length()));
        return chatService.createReactAgent(chatModel, systemPrompt);
    }

    private String buildFastReplyIfSimple(String question) {
        String normalized = normalizeSimpleInput(question);
        if (normalized.isEmpty() || normalized.length() > 12 || looksLikeWorkQuestion(normalized)) {
            return null;
        }
        if (SIMPLE_GREETINGS.contains(normalized)) {
            return SIMPLE_GREETING_REPLY;
        }
        if (SIMPLE_THANKS.contains(normalized)) {
            return THANKS_REPLY;
        }
        if (SIMPLE_IDENTITY_QUESTIONS.contains(normalized)) {
            return IDENTITY_REPLY;
        }
        return null;
    }

    private String normalizeSimpleInput(String question) {
        if (question == null) {
            return "";
        }
        return question
                .trim()
                .toLowerCase()
                .replaceAll("[\\s,，.。!！?？~～、;；:：\"'“”‘’()（）\\[\\]【】]+", "");
    }

    private boolean looksLikeWorkQuestion(String normalized) {
        return normalized.contains("cpu")
                || normalized.contains("内存")
                || normalized.contains("磁盘")
                || normalized.contains("告警")
                || normalized.contains("日志")
                || normalized.contains("故障")
                || normalized.contains("服务")
                || normalized.contains("接口")
                || normalized.contains("延迟")
                || normalized.contains("报错")
                || normalized.contains("排查")
                || normalized.contains("文档")
                || normalized.contains("rag")
                || normalized.contains("redis")
                || normalized.contains("milvus")
                || normalized.contains("prometheus");
    }

    private boolean shouldUseGroundedRag(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase();
        return normalized.contains("知识库")
                || normalized.contains("文档")
                || normalized.contains("runbook")
                || normalized.contains("引用")
                || normalized.contains("knowledge base")
                || normalized.contains("documentation")
                || normalized.contains("cite")
                || normalized.contains("citation")
                || normalized.contains("troubleshoot")
                || normalized.contains("investigate")
                || normalized.contains("排查")
                || normalized.contains("故障")
                || normalized.contains("redis")
                || normalized.contains("cache avalanche")
                || normalized.contains("cache hit rate")
                || normalized.contains("big key")
                || normalized.contains("slowlog")
                || normalized.contains("database query timeout")
                || normalized.contains("database connection")
                || normalized.contains("connection pool")
                || normalized.contains("connection leak")
                || normalized.contains("slow query")
                || normalized.contains("lock wait")
                || normalized.contains("consumer lag")
                || normalized.contains("poison message")
                || normalized.contains("schema mismatch")
                || normalized.contains("pod")
                || normalized.contains("liveness probe")
                || normalized.contains("bad deployment")
                || normalized.contains("invalid config")
                || normalized.contains("crashloopbackoff")
                || normalized.contains("oomkilled");
    }

    private String buildGroundedRagAnswer(String question) {
        TrustedRagRetrievalService.TrustedRagResult ragResult =
                trustedRagRetrievalService.retrieve(question, Math.max(ragTopK, 8));
        List<VectorSearchService.SearchResult> sources = focusedSources(question, ragResult.getResults());
        if (sources.isEmpty()) {
            return "知识库未检索到足够相关的证据，暂时不能给出有依据的排查结论。建议先补充相关 Runbook、日志样例或告警说明。";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("### 基于知识库的排查回答\n\n");
        answer.append("我只根据本次命中的知识库片段回答。用户问题中的服务名、现象描述会作为上下文使用；没有在资料中出现的命令、阈值或配置项不会扩写。\n\n");
        answer.append("- 原始问题：").append(question).append("\n");
        answer.append("- 实际检索问题：").append(ragResult.getPreprocess().finalQuery()).append("\n");
        answer.append("- 命中文档：").append(sourceFiles(sources)).append("\n\n");

        answer.append("### 证据摘要\n\n");
        for (VectorSearchService.SearchResult source : sources) {
            answer.append("**").append(citationLabel(source))
                    .append("**\n\n");
            answer.append(excerpt(source.getContent())).append("\n\n");
        }

        answer.append("### 建议处理步骤\n\n");
        int step = 1;
        Set<String> usedActions = new LinkedHashSet<>();
        for (VectorSearchService.SearchResult source : sources) {
            List<String> actions = linesForSection(source, SectionUse.ACTION);
            for (String action : actions) {
                if (!usedActions.add(normalizeActionLine(action))) {
                    continue;
                }
                answer.append(step++).append(". ")
                        .append(action)
                        .append("（来源：")
                        .append(citationLabel(source))
                        .append("）\n");
            }
        }
        if (step == 1) {
            answer.append("1. 当前命中资料主要是症状或关键词，缺少明确操作步骤；建议补充 First Response、Actions、Verification 等 Runbook 章节后再执行变更。\n");
        }

        appendSectionLines(answer, "### 安全边界\n\n", sources, SectionUse.SAFETY);
        appendSectionLines(answer, "### 验证指标\n\n", sources, SectionUse.VERIFICATION);

        FaithfulnessVerifierService.VerificationResult verification =
                faithfulnessVerifierService.verify(answer.toString(), sources);

        answer.append("\n### Faithfulness\n\n");
        answer.append("- 校验结果：").append(verification.passed() ? "通过" : "未通过").append("\n");
        answer.append("- 覆盖率：").append(String.format("%.4f", verification.coverage())).append("\n");
        answer.append("- 说明：").append(verification.reason()).append("\n");
        if (!verification.unsupportedClaims().isEmpty()) {
            answer.append("- 未被资料支持的表述：")
                    .append(String.join("；", verification.unsupportedClaims()))
                    .append("\n");
        }

        answer.append("\n### 参考来源\n\n");
        for (VectorSearchService.SearchResult source : sources) {
            answer.append("- ")
                    .append(citationLabel(source))
                    .append("，metadata=")
                    .append(source.getMetadata())
                    .append("\n");
        }
        return answer.toString();
    }

    private List<VectorSearchService.SearchResult> focusedSources(
            String question,
            List<VectorSearchService.SearchResult> sources
    ) {
        String preferred = preferredSource(question);
        if (preferred.isBlank()) {
            return sources;
        }
        List<VectorSearchService.SearchResult> focused = sources.stream()
                .filter(source -> sourceName(source).equals(preferred))
                .filter(source -> matchesPreferredSection(question, source))
                .sorted((left, right) -> Integer.compare(sourcePriority(question, left), sourcePriority(question, right)))
                .collect(Collectors.toMap(
                        source -> sourceName(source) + "|" + normalizedSectionTitle(source) + "|" + normalizedContentFingerprint(source),
                        source -> source,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                ))
                .values()
                .stream()
                .limit(ragTopK)
                .collect(Collectors.toList());
        return focused.isEmpty() ? sources : focused;
    }

    private String normalizedSectionTitle(VectorSearchService.SearchResult source) {
        String sectionTitle = metadataValue(source, "sectionTitle");
        if (sectionTitle.isBlank()) {
            sectionTitle = metadataValue(source, "title");
        }
        return sectionTitle.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String normalizedContentFingerprint(VectorSearchService.SearchResult source) {
        String content = source.getContent() == null ? "" : source.getContent();
        return content.toLowerCase()
                .replaceAll("[#`*_>\\-\\d\\.]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean matchesPreferredSection(String question, VectorSearchService.SearchResult source) {
        List<String> terms = preferredSectionTerms(question);
        if (terms.isEmpty()) {
            return true;
        }
        String searchable = String.join("\n",
                metadataValue(source, "title"),
                metadataValue(source, "sectionTitle"),
                metadataValue(source, "headingPath"),
                source.getContent() == null ? "" : source.getContent()
        ).toLowerCase();
        return terms.stream().anyMatch(searchable::contains);
    }

    private List<String> preferredSectionTerms(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        if (normalized.contains("cache avalanche") || normalized.contains("ttl avalanche") || normalized.contains("many cache keys expired")) {
            return List.of("cache avalanche");
        }
        if (normalized.contains("big key") || normalized.contains("slow command") || normalized.contains("slowlog")) {
            return List.of("big key", "slow command");
        }
        if (normalized.contains("hot key")) {
            return List.of("hot key");
        }
        if (normalized.contains("connection leak")) {
            return List.of("connection leak");
        }
        if (normalized.contains("lock wait") || normalized.contains("slow query")) {
            return List.of("slow sql", "lock contention");
        }
        if (normalized.contains("connection pool") || normalized.contains("连接池")) {
            return List.of("connection pool");
        }
        if (normalized.contains("poison message") || normalized.contains("schema mismatch")) {
            return List.of("poison message", "schema mismatch");
        }
        if (normalized.contains("downstream") || normalized.contains("consumer lag")) {
            return List.of("downstream dependency", "consumer lag", "backlog");
        }
        if (normalized.contains("liveness probe") || normalized.contains("readiness probe") || normalized.contains("probe")) {
            return List.of("probe misconfiguration", "liveness", "readiness");
        }
        if (normalized.contains("bad deployment") || normalized.contains("invalid config")) {
            return List.of("bad deployment", "invalid config");
        }
        if (normalized.contains("oomkilled") || normalized.contains("crashloopbackoff")) {
            return List.of("oomkilled", "crashloopbackoff", "pod restart");
        }
        return List.of();
    }

    private int sourcePriority(String question, VectorSearchService.SearchResult source) {
        String normalizedQuestion = question == null ? "" : question.toLowerCase();
        String headingPath = metadataValue(source, "headingPath").toLowerCase();
        String title = metadataValue(source, "title").toLowerCase();
        String semanticBlockType = metadataValue(source, "semanticBlockType").toLowerCase();
        String content = source.getContent() == null ? "" : source.getContent().toLowerCase();

        if (asksForSteps(normalizedQuestion)) {
            if (headingPath.contains("first response") || title.contains("first response")) {
                return 0;
            }
            if (semanticBlockType.equals("action") || headingPath.contains("actions") || title.contains("actions")) {
                return 1;
            }
            if (semanticBlockType.equals("root_cause")) {
                return 2;
            }
            if (semanticBlockType.equals("safety")) {
                return 4;
            }
            if (semanticBlockType.equals("verification")) {
                return 5;
            }
            if (semanticBlockType.equals("symptom")) {
                return 8;
            }
        }

        if (content.contains("actions:") || headingPath.contains("actions")) {
            return 1;
        }
        if (content.contains("evidence:") || semanticBlockType.equals("evidence")) {
            return 2;
        }
        return 5;
    }

    private boolean asksForSteps(String normalizedQuestion) {
        return normalizedQuestion.contains("step")
                || normalizedQuestion.contains("how should")
                || normalizedQuestion.contains("investigate")
                || normalizedQuestion.contains("排查")
                || normalizedQuestion.contains("步骤")
                || normalizedQuestion.contains("怎么");
    }

    private String preferredSource(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        if (normalized.contains("redis") || normalized.contains("缓存") || normalized.contains("cache")) {
            return "redis_timeout.md";
        }
        if (normalized.contains("consumer lag") || normalized.contains("mq") || normalized.contains("消息") || normalized.contains("队列")) {
            return "mq_backlog.md";
        }
        if (normalized.contains("database query timeout") || normalized.contains("connection pool")
                || normalized.contains("连接池") || normalized.contains("数据库")) {
            return "db_connection_pool.md";
        }
        if (normalized.contains("pod") || normalized.contains("crashloopbackoff") || normalized.contains("oomkilled")) {
            return "pod_restart.md";
        }
        return "";
    }

    private String sourceFiles(List<VectorSearchService.SearchResult> sources) {
        return sources.stream()
                .map(this::sourceName)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .collect(Collectors.joining(", "));
    }

    private int sourceIndex(VectorSearchService.SearchResult source) {
        return source.getSourceIndex() == null ? 0 : source.getSourceIndex();
    }

    private String sourceName(VectorSearchService.SearchResult source) {
        String metadata = source.getMetadata();
        if (metadata == null) {
            return "unknown";
        }
        int key = metadata.indexOf("\"_file_name\"");
        if (key < 0) {
            return "unknown";
        }
        int colon = metadata.indexOf(':', key);
        int firstQuote = metadata.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : metadata.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "unknown";
        }
        return metadata.substring(firstQuote + 1, secondQuote);
    }

    private String citationLabel(VectorSearchService.SearchResult source) {
        StringBuilder label = new StringBuilder();
        label.append("参考资料 ").append(sourceIndex(source)).append("：").append(sourceName(source));
        String headingPath = metadataValue(source, "headingPath");
        if (!headingPath.isBlank()) {
            label.append(" > ").append(headingPath);
        } else {
            String title = metadataValue(source, "title");
            if (!title.isBlank()) {
                label.append(" > ").append(title);
            }
        }
        String chunkIndex = metadataValue(source, "chunkIndex");
        if (!chunkIndex.isBlank()) {
            label.append(" > chunk ").append(chunkIndex);
        }
        return label.toString();
    }

    private String metadataValue(VectorSearchService.SearchResult source, String keyName) {
        String metadata = source.getMetadata();
        if (metadata == null || keyName == null || keyName.isBlank()) {
            return "";
        }
        String key = "\"" + keyName + "\"";
        int keyIndex = metadata.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }
        int colon = metadata.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return "";
        }
        int valueStart = colon + 1;
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= metadata.length()) {
            return "";
        }
        if (metadata.charAt(valueStart) == '"') {
            int valueEnd = metadata.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                return "";
            }
            return metadata.substring(valueStart + 1, valueEnd);
        }
        int comma = metadata.indexOf(',', valueStart);
        int endBrace = metadata.indexOf('}', valueStart);
        int valueEnd = comma < 0 ? endBrace : Math.min(comma, endBrace < 0 ? comma : endBrace);
        if (valueEnd < 0) {
            valueEnd = metadata.length();
        }
        return metadata.substring(valueStart, valueEnd).trim();
    }

    private String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "该片段为空。";
        }
        String normalized = content.trim();
        if (normalized.length() <= 1200) {
            return normalized;
        }
        return normalized.substring(0, 1200) + "...";
    }

    private void appendSectionLines(
            StringBuilder answer,
            String title,
            List<VectorSearchService.SearchResult> sources,
            SectionUse use
    ) {
        int index = 1;
        StringBuilder section = new StringBuilder(title);
        Set<String> usedLines = new LinkedHashSet<>();
        for (VectorSearchService.SearchResult source : sources) {
            for (String line : linesForSection(source, use)) {
                if (!usedLines.add(normalizeActionLine(line))) {
                    continue;
                }
                section.append(index++).append(". ")
                        .append(line)
                        .append("（来源：")
                        .append(citationLabel(source))
                        .append("）\n");
            }
        }
        if (index > 1) {
            answer.append("\n").append(section);
        }
    }

    private String normalizeActionLine(String line) {
        return line == null ? "" : line.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private List<String> linesForSection(VectorSearchService.SearchResult source, SectionUse use) {
        String content = source.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> markedLines = switch (use) {
            case ACTION -> linesAfterMarker(content, "Actions:");
            case SAFETY -> linesAfterMarker(content, "Safe Operations:");
            case VERIFICATION -> linesAfterMarker(content, "Verification:");
        };
        if (!markedLines.isEmpty()) {
            return markedLines;
        }
        String semanticBlockType = metadataValue(source, "semanticBlockType").toLowerCase();
        String headingPath = metadataValue(source, "headingPath").toLowerCase();
        String title = metadataValue(source, "title").toLowerCase();
        boolean actionLike = semanticBlockType.equals("action")
                || semanticBlockType.equals("root_cause")
                || headingPath.contains("first response")
                || headingPath.contains("actions")
                || title.contains("first response")
                || title.contains("actions");
        boolean safetyLike = semanticBlockType.equals("safety")
                || headingPath.contains("safe operations")
                || title.contains("safe operations")
                || title.contains("safe");
        boolean verificationLike = semanticBlockType.equals("verification")
                || headingPath.contains("verification")
                || title.contains("verification");

        if (use == SectionUse.ACTION && !actionLike) {
            return List.of();
        }
        if (use == SectionUse.SAFETY && !safetyLike) {
            return List.of();
        }
        if (use == SectionUse.VERIFICATION && !verificationLike) {
            return List.of();
        }
        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> line.length() >= 12)
                .filter(line -> Character.isUpperCase(line.charAt(0)) || Character.isDigit(line.charAt(0)) || line.startsWith("-"))
                .filter(line -> !line.equalsIgnoreCase("Evidence:"))
                .filter(line -> !line.equalsIgnoreCase("Actions:"))
                .filter(line -> !line.startsWith("umer "))
                .filter(line -> line.matches("^(\\d+\\.|- |Actions:|Evidence:|Check|Confirm|Identify|Compare|Search|Preserve|Reduce|Tune|Add|Split|Replace|Do not|Avoid|Prefer|Temporarily|Enable|Inspect|Roll back|Validate|Query).*"))
                .map(line -> line.replaceFirst("^\\d+\\.\\s*", ""))
                .map(line -> line.replaceFirst("^-\\s*", ""))
                .limit(8)
                .collect(Collectors.toList());
    }

    private List<String> linesAfterMarker(String content, String marker) {
        List<String> result = new ArrayList<>();
        boolean collecting = false;
        for (String rawLine : content.lines().toList()) {
            String line = rawLine.trim();
            if (line.equalsIgnoreCase(marker)) {
                collecting = true;
                continue;
            }
            if (!collecting) {
                continue;
            }
            if (isSectionBoundary(line)) {
                break;
            }
            if (isUsableSectionLine(line)) {
                result.add(cleanSectionLine(line));
            }
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    private boolean isSectionBoundary(String line) {
        if (line.isBlank()) {
            return false;
        }
        return line.startsWith("#")
                || line.equalsIgnoreCase("Evidence:")
                || line.equalsIgnoreCase("Actions:")
                || line.equalsIgnoreCase("Safe Operations:")
                || line.equalsIgnoreCase("Verification:")
                || line.equalsIgnoreCase("Symptoms:")
                || line.equalsIgnoreCase("Root Cause Candidates:");
    }

    private boolean isUsableSectionLine(String line) {
        if (line.isBlank() || line.length() < 12) {
            return false;
        }
        return line.startsWith("-")
                || line.matches("^\\d+\\.\\s+.*")
                || line.matches("^(Check|Confirm|Identify|Compare|Search|Preserve|Reduce|Tune|Add|Split|Replace|Do not|Avoid|Prefer|Temporarily|Enable|Inspect|Roll back|Validate|Query|Monitor|Restart|Scale|Throttle|Pause|Resume).*");
    }

    private String cleanSectionLine(String line) {
        return line.replaceFirst("^\\d+\\.\\s*", "")
                .replaceFirst("^-\\s*", "")
                .trim();
    }

    private enum SectionUse {
        ACTION,
        SAFETY,
        VERIFICATION
    }

    private void handleStreamOutput(NodeOutput output, StringBuilder fullAnswerBuilder, StreamHandler handler) {
        if (output instanceof StreamingOutput streamingOutput) {
            OutputType type = streamingOutput.getOutputType();
            if (type == OutputType.AGENT_MODEL_STREAMING) {
                String chunk = streamingOutput.message().getText();
                if (chunk != null && !chunk.isEmpty()) {
                    fullAnswerBuilder.append(chunk);
                    handler.onContent(chunk);
                }
            }
        }
    }

    private void handleStreamError(Throwable error, StreamHandler handler, String traceId) {
        logger.error("ReactAgent stream failed", error);
        traceService.finishTrace(traceId, TraceStatus.ERROR, error.getMessage());
        handler.onError(error);
    }

    private void handleStreamComplete(
            String sessionId,
            String question,
            StringBuilder fullAnswerBuilder,
            StreamHandler handler,
            String traceId
    ) {
        try (TraceContext.Scope ignored = traceService.bind(traceId)) {
            String originalAnswer = fullAnswerBuilder.toString();
            traceService.event("chat_stream", "agent_stream_complete", Map.of("answerLength", originalAnswer.length()));
            String verifiedAnswer = chatAnswerVerificationService.verifyIfNeeded(question, originalAnswer);

            String appendedVerification = verifiedAnswer.substring(
                    Math.min(originalAnswer.length(), verifiedAnswer.length())
            );
            if (!appendedVerification.isEmpty()) {
                handler.onContent(appendedVerification);
            }

            chatMemoryService.addMessage(sessionId, question, verifiedAnswer);
            traceService.finishTrace(traceId, TraceStatus.SUCCESS, null);
            handler.onDone();
        }
    }

    public record ChatResult(String sessionId, String answer, String traceId) {
    }

    public interface StreamHandler {
        void onSession(String sessionId);

        void onTrace(String traceId);

        void onContent(String chunk);

        void onDone();

        void onError(Throwable error);
    }
}
