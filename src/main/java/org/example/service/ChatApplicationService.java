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
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
public class ChatApplicationService {
    private static final Logger logger = LoggerFactory.getLogger(ChatApplicationService.class);

    private final ChatService chatService;
    private final ChatAnswerVerificationService chatAnswerVerificationService;
    private final ChatMemoryService chatMemoryService;
    private final AgentTraceService traceService;

    public ChatApplicationService(
            ChatService chatService,
            ChatAnswerVerificationService chatAnswerVerificationService,
            ChatMemoryService chatMemoryService,
            AgentTraceService traceService
    ) {
        this.chatService = chatService;
        this.chatAnswerVerificationService = chatAnswerVerificationService;
        this.chatMemoryService = chatMemoryService;
        this.traceService = traceService;
    }

    public ChatResult chat(String requestedSessionId, String question) throws Exception {
        String traceId = traceService.startTrace("chat", question);
        try (TraceContext.Scope ignored = traceService.bind(traceId)) {
            String sessionId = chatMemoryService.resolveSessionId(requestedSessionId);
            traceService.event("chat", "session_resolved", Map.of("sessionId", sessionId));
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
