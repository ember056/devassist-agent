package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.dto.AIOpsRequest;
import org.example.service.AiOpsService;
import org.example.service.ChatAnswerVerificationService;
import org.example.service.ChatMemoryService;
import org.example.service.ChatService;
import org.example.service.aiops.AiOpsAnalysisResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
public class ChatController {
    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    private final AiOpsService aiOpsService;
    private final ChatService chatService;
    private final ChatAnswerVerificationService chatAnswerVerificationService;
    private final ChatMemoryService chatMemoryService;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public ChatController(
            AiOpsService aiOpsService,
            ChatService chatService,
            ChatAnswerVerificationService chatAnswerVerificationService,
            ChatMemoryService chatMemoryService
    ) {
        this.aiOpsService = aiOpsService;
        this.chatService = chatService;
        this.chatAnswerVerificationService = chatAnswerVerificationService;
        this.chatMemoryService = chatMemoryService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("Question cannot be empty")));
            }

            String sessionId = chatMemoryService.resolveSessionId(request.getId());
            List<Map<String, String>> history = chatMemoryService.getHistory(sessionId);
            history.addAll(chatMemoryService.getRelevantSemanticMemories(sessionId, request.getQuestion()));

            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
            chatService.logAvailableTools();

            String systemPrompt = chatService.buildSystemPrompt(history);
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            String fullAnswer = chatService.executeChat(agent, request.getQuestion());
            fullAnswer = chatAnswerVerificationService.verifyIfNeeded(request.getQuestion(), fullAnswer);

            chatMemoryService.addMessage(sessionId, request.getQuestion(), fullAnswer);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(sessionId, fullAnswer)));
        } catch (Exception e) {
            logger.error("Chat request failed", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("Session id cannot be empty"));
            }

            boolean cleared = chatMemoryService.clearHistory(request.getId());
            return cleared
                    ? ResponseEntity.ok(ApiResponse.success("Chat history cleared"))
                    : ResponseEntity.ok(ApiResponse.error("Session does not exist"));
        } catch (Exception e) {
            logger.error("Clear chat history failed", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L);

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            sendAndComplete(emitter, SseMessage.error("Question cannot be empty"));
            return emitter;
        }

        executor.execute(() -> {
            try {
                String sessionId = chatMemoryService.resolveSessionId(request.getId());
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.session(sessionId), MediaType.APPLICATION_JSON));

                List<Map<String, String>> history = chatMemoryService.getHistory(sessionId);
                history.addAll(chatMemoryService.getRelevantSemanticMemories(sessionId, request.getQuestion()));

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);
                chatService.logAvailableTools();

                String systemPrompt = chatService.buildSystemPrompt(history);
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                StringBuilder fullAnswerBuilder = new StringBuilder();
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                stream.subscribe(
                        output -> handleStreamOutput(output, fullAnswerBuilder, emitter),
                        error -> handleStreamError(error, emitter),
                        () -> handleStreamComplete(request, sessionId, fullAnswerBuilder, emitter)
                );
            } catch (Exception e) {
                logger.error("Chat stream initialization failed", e);
                sendError(emitter, e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps(@RequestBody(required = false) AIOpsRequest request) {
        SseEmitter emitter = new SseEmitter(600000L);

        executor.execute(() -> {
            try {
                String incidentRequest = request == null ? null : request.getUserRequest();
                logger.info("Received AI Ops request - starting Hypothesis Graph workflow");

                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("Building hypothesis graph and collecting evidence...\n"), MediaType.APPLICATION_JSON));

                AiOpsAnalysisResult result = aiOpsService.executeAiOpsAnalysis(incidentRequest);
                sendChunked(emitter, result.getReport(), 80);

                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops Hypothesis Graph workflow completed");
            } catch (Exception e) {
                logger.error("AI Ops Hypothesis Graph workflow failed", e);
                sendError(emitter, "AI Ops analysis failed: " + e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            ChatMemoryService.SessionSummary sessionSummary = chatMemoryService.getSessionSummary(sessionId);
            if (sessionSummary == null) {
                return ResponseEntity.ok(ApiResponse.error("Session does not exist"));
            }

            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(sessionSummary.sessionId());
            response.setMessagePairCount(sessionSummary.messagePairCount());
            response.setCreateTime(sessionSummary.createTime());
            response.setBackend(sessionSummary.backend());
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (Exception e) {
            logger.error("Get session info failed", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    private void handleStreamOutput(NodeOutput output, StringBuilder fullAnswerBuilder, SseEmitter emitter) {
        try {
            if (output instanceof StreamingOutput streamingOutput) {
                OutputType type = streamingOutput.getOutputType();
                if (type == OutputType.AGENT_MODEL_STREAMING) {
                    String chunk = streamingOutput.message().getText();
                    if (chunk != null && !chunk.isEmpty()) {
                        fullAnswerBuilder.append(chunk);
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void handleStreamError(Throwable error, SseEmitter emitter) {
        logger.error("ReactAgent stream failed", error);
        sendError(emitter, error.getMessage());
        emitter.completeWithError(error);
    }

    private void handleStreamComplete(
            ChatRequest request,
            String sessionId,
            StringBuilder fullAnswerBuilder,
            SseEmitter emitter
    ) {
        try {
            String fullAnswer = fullAnswerBuilder.toString();
            fullAnswer = chatAnswerVerificationService.verifyIfNeeded(request.getQuestion(), fullAnswer);

            String appendedVerification = fullAnswer.substring(
                    Math.min(fullAnswerBuilder.length(), fullAnswer.length())
            );
            if (!appendedVerification.isEmpty()) {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content(appendedVerification), MediaType.APPLICATION_JSON));
            }

            chatMemoryService.addMessage(sessionId, request.getQuestion(), fullAnswer);
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendChunked(SseEmitter emitter, String text, int chunkSize) throws IOException {
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
        for (int i = 0; i < text.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, text.length());
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content(text.substring(i, end)), MediaType.APPLICATION_JSON));
        }
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
    }

    private void sendAndComplete(SseEmitter emitter, SseMessage message) {
        try {
            emitter.send(SseEmitter.event().name("message").data(message, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void sendError(SseEmitter emitter, String errorMessage) {
        try {
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.error(errorMessage), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            logger.error("Failed to send SSE error message", e);
        }
    }

    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;

        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;
    }

    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
        private String backend;
    }

    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String sessionId;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String sessionId, String answer) {
            ChatResponse response = success(answer);
            response.setSessionId(sessionId);
            return response;
        }

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    @Setter
    @Getter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage session(String sessionId) {
            SseMessage message = new SseMessage();
            message.setType("session");
            message.setData(sessionId);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }
    }
}
