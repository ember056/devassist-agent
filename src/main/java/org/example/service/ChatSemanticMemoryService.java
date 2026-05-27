package org.example.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
import org.example.constant.MilvusConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话语义记忆服务。
 * Redis 保存短期窗口，Milvus 保存可语义召回的长期会话记忆。
 */
@Service
public class ChatSemanticMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatSemanticMemoryService.class);

    @Autowired
    private MilvusServiceClient milvusClient;

    @Autowired
    private VectorEmbeddingService embeddingService;

    @Value("${chat.memory.semantic-enabled:true}")
    private boolean semanticEnabled;

    @Value("${chat.memory.semantic-top-k:3}")
    private int semanticTopK;

    @Value("${chat.memory.semantic-max-answer-length:1200}")
    private int maxAnswerLength;

    private final Gson gson = new Gson();

    public void saveTurn(String sessionId, String userQuestion, String aiAnswer) {
        if (!semanticEnabled || sessionId == null || sessionId.isBlank()
                || userQuestion == null || userQuestion.isBlank()) {
            return;
        }

        try {
            String memoryText = buildMemoryText(userQuestion, aiAnswer);
            List<Float> vector = embeddingService.generateEmbedding(memoryText);
            Map<String, Object> metadata = Map.of(
                    "sessionId", sessionId,
                    "type", "chat_turn",
                    "createdAt", System.currentTimeMillis()
            );

            insertMemory(sessionId, memoryText, vector, metadata);
        } catch (Exception e) {
            logger.warn("写入会话语义记忆失败，不影响短期记忆: {}", e.getMessage());
        }
    }

    public List<SemanticMemory> search(String sessionId, String query) {
        if (!semanticEnabled || sessionId == null || sessionId.isBlank()
                || query == null || query.isBlank()) {
            return List.of();
        }

        try {
            loadCollection();
            List<Float> queryVector = embeddingService.generateQueryVector(query);

            SearchParam searchParam = SearchParam.newBuilder()
                    .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                    .withVectorFieldName("vector")
                    .withVectors(Collections.singletonList(queryVector))
                    .withTopK(Math.max(1, semanticTopK))
                    .withMetricType(MetricType.L2)
                    .withOutFields(List.of("id", "content", "metadata"))
                    .withExpr("metadata[\"sessionId\"] == \"" + escapeExpr(sessionId) + "\"")
                    .withParams("{\"nprobe\":10}")
                    .build();

            R<SearchResults> searchResponse = milvusClient.search(searchParam);
            if (searchResponse.getStatus() != 0) {
                logger.warn("会话语义记忆检索失败: {}", searchResponse.getMessage());
                return List.of();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(searchResponse.getData().getResults());
            List<SemanticMemory> memories = new ArrayList<>();
            for (int i = 0; i < wrapper.getRowRecords(0).size(); i++) {
                String id = (String) wrapper.getIDScore(0).get(i).get("id");
                String content = (String) wrapper.getFieldData("content", 0).get(i);
                float score = wrapper.getIDScore(0).get(i).getScore();
                Object metadataObj = wrapper.getFieldData("metadata", 0).get(i);
                memories.add(new SemanticMemory(
                        id,
                        content,
                        score,
                        metadataObj == null ? null : metadataObj.toString()
                ));
            }
            return memories;
        } catch (Exception e) {
            logger.warn("检索会话语义记忆失败，跳过长期记忆: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteSession(String sessionId) {
        if (!semanticEnabled || sessionId == null || sessionId.isBlank()) {
            return;
        }

        try {
            loadCollection();
            DeleteParam deleteParam = DeleteParam.newBuilder()
                    .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                    .withExpr("metadata[\"sessionId\"] == \"" + escapeExpr(sessionId) + "\"")
                    .build();
            R<MutationResult> response = milvusClient.delete(deleteParam);
            if (response.getStatus() != 0) {
                logger.warn("删除会话语义记忆失败: {}", response.getMessage());
            }
        } catch (Exception e) {
            logger.warn("删除会话语义记忆异常: {}", e.getMessage());
        }
    }

    private void insertMemory(
            String sessionId,
            String content,
            List<Float> vector,
            Map<String, Object> metadata
    ) {
        loadCollection();

        String id = UUID.nameUUIDFromBytes((sessionId + "_" + content + "_" + System.currentTimeMillis())
                .getBytes(StandardCharsets.UTF_8)).toString();

        JsonObject metadataJson = gson.toJsonTree(metadata).getAsJsonObject();

        List<InsertParam.Field> fields = new ArrayList<>();
        fields.add(new InsertParam.Field("id", Collections.singletonList(id)));
        fields.add(new InsertParam.Field("content", Collections.singletonList(content)));
        fields.add(new InsertParam.Field("vector", Collections.singletonList(vector)));
        fields.add(new InsertParam.Field("metadata", Collections.singletonList(metadataJson)));

        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                .withFields(fields)
                .build();

        R<MutationResult> response = milvusClient.insert(insertParam);
        if (response.getStatus() != 0) {
            throw new RuntimeException("插入会话语义记忆失败: " + response.getMessage());
        }
    }

    private void loadCollection() {
        R<RpcStatus> loadResponse = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(MilvusConstants.CHAT_MEMORY_COLLECTION_NAME)
                        .build()
        );
        if (loadResponse.getStatus() != 0 && loadResponse.getStatus() != 65535) {
            throw new RuntimeException("加载会话记忆 collection 失败: " + loadResponse.getMessage());
        }
    }

    private String buildMemoryText(String userQuestion, String aiAnswer) {
        String answer = aiAnswer == null ? "" : aiAnswer.trim();
        if (answer.length() > maxAnswerLength) {
            answer = answer.substring(0, maxAnswerLength) + "...";
        }

        return """
                用户问题：
                %s

                助手回答：
                %s
                """.formatted(userQuestion.trim(), answer);
    }

    private String escapeExpr(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record SemanticMemory(
            String id,
            String content,
            float score,
            String metadata
    ) {
    }
}
