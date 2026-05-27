package org.example.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 聊天记忆服务。
 * Redis 可用时使用 Redis List 持久化最近 N 轮会话；Redis 不可用时回退本地内存。
 */
@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, LocalSessionInfo> fallbackSessions = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ChatSemanticMemoryService chatSemanticMemoryService;

    @Value("${chat.memory.redis-enabled:true}")
    private boolean redisEnabled;

    @Value("${chat.memory.key-prefix:onecall:session}")
    private String keyPrefix;

    @Value("${chat.memory.max-pairs:6}")
    private int maxPairs;

    @Value("${chat.memory.ttl-hours:168}")
    private long ttlHours;

    public String resolveSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    public List<Map<String, String>> getHistory(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        if (isRedisUsable()) {
            try {
                List<String> rawMessages = redisTemplate.opsForList()
                        .range(messagesKey(resolvedSessionId), 0, -1);
                if (rawMessages == null || rawMessages.isEmpty()) {
                    ensureCreateTime(resolvedSessionId);
                    return new ArrayList<>();
                }

                List<Map<String, String>> history = new ArrayList<>();
                for (String rawMessage : rawMessages) {
                    history.add(toHistoryMap(objectMapper.readValue(rawMessage, MemoryMessage.class)));
                }
                refreshTtl(resolvedSessionId);
                return history;
            } catch (Exception e) {
                logger.warn("读取 Redis 会话历史失败，回退本地内存: {}", e.getMessage());
            }
        }
        return fallbackSession(resolvedSessionId).getHistory();
    }

    public void addMessage(String sessionId, String userQuestion, String aiAnswer) {
        String resolvedSessionId = resolveSessionId(sessionId);
        if (isRedisUsable()) {
            try {
                String key = messagesKey(resolvedSessionId);
                redisTemplate.opsForList().rightPush(key, toJson(new MemoryMessage("user", userQuestion)));
                redisTemplate.opsForList().rightPush(key, toJson(new MemoryMessage("assistant", aiAnswer)));
                redisTemplate.opsForList().trim(key, -maxMessages(), -1);
                ensureCreateTime(resolvedSessionId);
                refreshTtl(resolvedSessionId);
                chatSemanticMemoryService.saveTurn(resolvedSessionId, userQuestion, aiAnswer);
                logger.debug("Redis 会话 {} 已更新，当前消息对数: {}", resolvedSessionId, getMessagePairCount(resolvedSessionId));
                return;
            } catch (Exception e) {
                logger.warn("写入 Redis 会话历史失败，回退本地内存: {}", e.getMessage());
            }
        }
        fallbackSession(resolvedSessionId).addMessage(userQuestion, aiAnswer, maxPairs);
        chatSemanticMemoryService.saveTurn(resolvedSessionId, userQuestion, aiAnswer);
    }

    public List<Map<String, String>> getRelevantSemanticMemories(String sessionId, String query) {
        List<ChatSemanticMemoryService.SemanticMemory> semanticMemories =
                chatSemanticMemoryService.search(resolveSessionId(sessionId), query);
        List<Map<String, String>> memoryMessages = new ArrayList<>();
        if (semanticMemories.isEmpty()) {
            return memoryMessages;
        }

        StringBuilder memoryBlock = new StringBuilder();
        memoryBlock.append("以下是从长期语义记忆中召回的相关历史对话，仅作为上下文参考：\n");
        for (int i = 0; i < semanticMemories.size(); i++) {
            ChatSemanticMemoryService.SemanticMemory memory = semanticMemories.get(i);
            memoryBlock.append("\n【长期记忆 ").append(i + 1).append("】score=")
                    .append(memory.score())
                    .append("\n")
                    .append(memory.content())
                    .append("\n");
        }

        Map<String, String> memoryMessage = new HashMap<>();
        memoryMessage.put("role", "assistant");
        memoryMessage.put("content", memoryBlock.toString());
        memoryMessages.add(memoryMessage);
        return memoryMessages;
    }

    public boolean clearHistory(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        boolean existed = false;

        if (isRedisUsable()) {
            try {
                String key = messagesKey(resolvedSessionId);
                existed = Boolean.TRUE.equals(redisTemplate.hasKey(key));
                redisTemplate.delete(List.of(key, createTimeKey(resolvedSessionId)));
                chatSemanticMemoryService.deleteSession(resolvedSessionId);
                return existed;
            } catch (Exception e) {
                logger.warn("清空 Redis 会话历史失败，回退本地内存: {}", e.getMessage());
            }
        }

        LocalSessionInfo sessionInfo = fallbackSessions.get(resolvedSessionId);
        if (sessionInfo != null) {
            sessionInfo.clearHistory();
            chatSemanticMemoryService.deleteSession(resolvedSessionId);
            return true;
        }
        return false;
    }

    public SessionSummary getSessionSummary(String sessionId) {
        String resolvedSessionId = resolveSessionId(sessionId);
        if (isRedisUsable()) {
            try {
                String key = messagesKey(resolvedSessionId);
                if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
                    return null;
                }
                Long size = redisTemplate.opsForList().size(key);
                long createTime = getCreateTime(resolvedSessionId);
                return new SessionSummary(resolvedSessionId, size == null ? 0 : (int) (size / 2), createTime, "redis");
            } catch (Exception e) {
                logger.warn("读取 Redis 会话摘要失败，回退本地内存: {}", e.getMessage());
            }
        }

        LocalSessionInfo sessionInfo = fallbackSessions.get(resolvedSessionId);
        if (sessionInfo == null) {
            return null;
        }
        return new SessionSummary(resolvedSessionId, sessionInfo.getMessagePairCount(), sessionInfo.getCreateTime(), "local");
    }

    public int getMessagePairCount(String sessionId) {
        SessionSummary summary = getSessionSummary(sessionId);
        return summary == null ? 0 : summary.messagePairCount();
    }

    private boolean isRedisUsable() {
        return redisEnabled && redisTemplate != null;
    }

    private int maxMessages() {
        return Math.max(1, maxPairs) * 2;
    }

    private String messagesKey(String sessionId) {
        return keyPrefix + ":" + sessionId + ":messages";
    }

    private String createTimeKey(String sessionId) {
        return keyPrefix + ":" + sessionId + ":createTime";
    }

    private void ensureCreateTime(String sessionId) {
        String key = createTimeKey(sessionId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.opsForValue().set(key, String.valueOf(System.currentTimeMillis()));
        }
    }

    private long getCreateTime(String sessionId) {
        String rawCreateTime = redisTemplate.opsForValue().get(createTimeKey(sessionId));
        if (rawCreateTime == null) {
            long now = System.currentTimeMillis();
            redisTemplate.opsForValue().set(createTimeKey(sessionId), String.valueOf(now));
            return now;
        }
        try {
            return Long.parseLong(rawCreateTime);
        } catch (NumberFormatException e) {
            long now = System.currentTimeMillis();
            redisTemplate.opsForValue().set(createTimeKey(sessionId), String.valueOf(now));
            return now;
        }
    }

    private void refreshTtl(String sessionId) {
        if (ttlHours <= 0) {
            return;
        }
        Duration ttl = Duration.ofHours(ttlHours);
        redisTemplate.expire(messagesKey(sessionId), ttl);
        redisTemplate.expire(createTimeKey(sessionId), ttl);
    }

    private String toJson(MemoryMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }

    private Map<String, String> toHistoryMap(MemoryMessage message) {
        Map<String, String> map = new HashMap<>();
        map.put("role", message.role());
        map.put("content", message.content());
        return map;
    }

    private LocalSessionInfo fallbackSession(String sessionId) {
        return fallbackSessions.computeIfAbsent(sessionId, ignored -> new LocalSessionInfo());
    }

    private record MemoryMessage(String role, String content, long timestamp) {
        private MemoryMessage(String role, String content) {
            this(role, content, System.currentTimeMillis());
        }
    }

    public record SessionSummary(
            String sessionId,
            int messagePairCount,
            long createTime,
            String backend
    ) {
    }

    private static class LocalSessionInfo {
        private final List<Map<String, String>> messageHistory;
        @Getter
        private final long createTime;
        private final ReentrantLock lock;

        private LocalSessionInfo() {
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        private void addMessage(String userQuestion, String aiAnswer, int maxPairs) {
            lock.lock();
            try {
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                int maxMessages = Math.max(1, maxPairs) * 2;
                while (messageHistory.size() > maxMessages) {
                    messageHistory.remove(0);
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0);
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        private List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        private void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
            } finally {
                lock.unlock();
            }
        }

        private int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }
}
