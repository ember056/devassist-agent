package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
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

    public ChatApplicationService(
            ChatService chatService,
            ChatAnswerVerificationService chatAnswerVerificationService,
            ChatMemoryService chatMemoryService
    ) {
        this.chatService = chatService;
        this.chatAnswerVerificationService = chatAnswerVerificationService;
        this.chatMemoryService = chatMemoryService;
    }

    public ChatResult chat(String requestedSessionId, String question) throws Exception {
        String sessionId = chatMemoryService.resolveSessionId(requestedSessionId);
        ReactAgent agent = createAgent(sessionId, question);

        String fullAnswer = chatService.executeChat(agent, question);
        fullAnswer = chatAnswerVerificationService.verifyIfNeeded(question, fullAnswer);

        chatMemoryService.addMessage(sessionId, question, fullAnswer);
        return new ChatResult(sessionId, fullAnswer);
    }

    public void streamChat(String requestedSessionId, String question, StreamHandler handler) throws Exception {
        String sessionId = chatMemoryService.resolveSessionId(requestedSessionId);
        handler.onSession(sessionId);

        ReactAgent agent = createAgent(sessionId, question);
        StringBuilder fullAnswerBuilder = new StringBuilder();
        Flux<NodeOutput> stream = agent.stream(question);

        stream.subscribe(
                output -> handleStreamOutput(output, fullAnswerBuilder, handler),
                error -> handleStreamError(error, handler),
                () -> handleStreamComplete(sessionId, question, fullAnswerBuilder, handler)
        );
    }

    public boolean clearHistory(String sessionId) {
        return chatMemoryService.clearHistory(sessionId);
    }

    public ChatMemoryService.SessionSummary getSessionSummary(String sessionId) {
        return chatMemoryService.getSessionSummary(sessionId);
    }

    private ReactAgent createAgent(String sessionId, String question) {
        List<Map<String, String>> history = chatMemoryService.getHistory(sessionId);
        history.addAll(chatMemoryService.getRelevantSemanticMemories(sessionId, question));

        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
        chatService.logAvailableTools();

        String systemPrompt = chatService.buildSystemPrompt(history);
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

    private void handleStreamError(Throwable error, StreamHandler handler) {
        logger.error("ReactAgent stream failed", error);
        handler.onError(error);
    }

    private void handleStreamComplete(
            String sessionId,
            String question,
            StringBuilder fullAnswerBuilder,
            StreamHandler handler
    ) {
        String originalAnswer = fullAnswerBuilder.toString();
        String verifiedAnswer = chatAnswerVerificationService.verifyIfNeeded(question, originalAnswer);

        String appendedVerification = verifiedAnswer.substring(
                Math.min(originalAnswer.length(), verifiedAnswer.length())
        );
        if (!appendedVerification.isEmpty()) {
            handler.onContent(appendedVerification);
        }

        chatMemoryService.addMessage(sessionId, question, verifiedAnswer);
        handler.onDone();
    }

    public record ChatResult(String sessionId, String answer) {
    }

    public interface StreamHandler {
        void onSession(String sessionId);

        void onContent(String chunk);

        void onDone();

        void onError(Throwable error);
    }
}
