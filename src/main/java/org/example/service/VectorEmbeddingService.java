package org.example.service;

import com.alibaba.dashscope.embeddings.TextEmbedding;
import com.alibaba.dashscope.embeddings.TextEmbeddingOutput;
import com.alibaba.dashscope.embeddings.TextEmbeddingParam;
import com.alibaba.dashscope.embeddings.TextEmbeddingResult;
import com.alibaba.dashscope.embeddings.TextEmbeddingResultItem;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VectorEmbeddingService {
    private static final Logger logger = LoggerFactory.getLogger(VectorEmbeddingService.class);

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${dashscope.embedding.model}")
    private String model;

    @Value("${dashscope.embedding.query-cache.enabled:true}")
    private boolean queryCacheEnabled;

    @Value("${dashscope.embedding.query-cache.max-size:10000}")
    private int queryCacheMaxSize;

    @Value("${dashscope.embedding.query-cache.ttl-seconds:1800}")
    private long queryCacheTtlSeconds;

    private TextEmbedding textEmbedding;
    private final Map<String, CacheEntry<List<Float>>> queryVectorCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (apiKey == null || apiKey.trim().isEmpty() || apiKey.equals("your-api-key-here")) {
            throw new IllegalStateException("Please configure DASHSCOPE_API_KEY before starting the service.");
        }

        Constants.apiKey = apiKey;
        textEmbedding = new TextEmbedding();
        logger.info(
                "DashScope embedding initialized. model={}, queryCacheEnabled={}, queryCacheMaxSize={}, queryCacheTtlSeconds={}",
                model,
                queryCacheEnabled,
                queryCacheMaxSize,
                queryCacheTtlSeconds
        );
    }

    public List<Float> generateEmbedding(String content) {
        try {
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalArgumentException("Embedding content cannot be empty");
            }

            ensureApiKey();
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(Collections.singletonList(content))
                    .build();

            TextEmbeddingResult result = textEmbedding.call(param);
            List<Float> embedding = getFloats(result);
            logger.info("Generated embedding. contentLength={}, dimensions={}", content.length(), embedding.size());
            return embedding;
        } catch (NoApiKeyException e) {
            throw new RuntimeException("DashScope API key is missing or invalid", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate embedding: " + e.getMessage(), e);
        }
    }

    public List<List<Float>> generateEmbeddings(List<String> contents) {
        try {
            if (contents == null || contents.isEmpty()) {
                return Collections.emptyList();
            }

            ensureApiKey();
            TextEmbeddingParam param = TextEmbeddingParam
                    .builder()
                    .model(model)
                    .texts(contents)
                    .build();

            TextEmbeddingResult result = textEmbedding.call(param);
            if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
                throw new RuntimeException("DashScope returned empty batch embedding result");
            }

            List<List<Float>> embeddings = new ArrayList<>();
            for (TextEmbeddingResultItem item : result.getOutput().getEmbeddings()) {
                embeddings.add(toFloatList(item.getEmbedding()));
            }

            logger.info("Generated batch embeddings. count={}", embeddings.size());
            return embeddings;
        } catch (NoApiKeyException e) {
            throw new RuntimeException("DashScope API key is missing or invalid", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate batch embeddings: " + e.getMessage(), e);
        }
    }

    public List<Float> generateQueryVector(String query) {
        if (!queryCacheEnabled) {
            return generateEmbedding(query);
        }

        String cacheKey = normalizeQuery(query);
        if (cacheKey.isBlank()) {
            return generateEmbedding(query);
        }

        CacheEntry<List<Float>> cached = queryVectorCache.get(cacheKey);
        if (cached != null && !cached.expired()) {
            logger.debug("Query embedding cache hit. key={}", cacheKey);
            return new ArrayList<>(cached.value());
        }

        List<Float> vector = generateEmbedding(query);
        putQueryVector(cacheKey, vector);
        return vector;
    }

    public float calculateCosineSimilarity(List<Float> vector1, List<Float> vector2) {
        if (vector1.size() != vector2.size()) {
            throw new IllegalArgumentException("Vector dimensions do not match");
        }

        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;

        for (int i = 0; i < vector1.size(); i++) {
            dotProduct += vector1.get(i) * vector2.get(i);
            norm1 += vector1.get(i) * vector1.get(i);
            norm2 += vector2.get(i) * vector2.get(i);
        }

        return dotProduct / (float) (Math.sqrt(norm1) * Math.sqrt(norm2));
    }

    @NotNull
    private static List<Float> getFloats(TextEmbeddingResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getEmbeddings() == null) {
            throw new RuntimeException("DashScope returned empty embedding result");
        }

        TextEmbeddingOutput output = result.getOutput();
        List<TextEmbeddingResultItem> embeddings = output.getEmbeddings();
        if (embeddings.isEmpty()) {
            throw new RuntimeException("DashScope returned no embeddings");
        }

        return toFloatList(embeddings.get(0).getEmbedding());
    }

    private static List<Float> toFloatList(List<Double> embeddingDoubles) {
        List<Float> result = new ArrayList<>(embeddingDoubles.size());
        for (Double value : embeddingDoubles) {
            result.add(value.floatValue());
        }
        return result;
    }

    private void putQueryVector(String cacheKey, List<Float> vector) {
        if (queryCacheMaxSize > 0 && queryVectorCache.size() >= queryCacheMaxSize) {
            queryVectorCache.clear();
            logger.info("Query embedding cache cleared after reaching max size: {}", queryCacheMaxSize);
        }
        queryVectorCache.put(cacheKey, new CacheEntry<>(List.copyOf(vector), queryCacheTtlSeconds));
    }

    private String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private void ensureApiKey() {
        if (Constants.apiKey == null || Constants.apiKey.isEmpty()) {
            Constants.apiKey = apiKey;
        }
    }

    private static class CacheEntry<T> {
        private final T value;
        private final long expiresAtMillis;

        CacheEntry(T value, long ttlSeconds) {
            this.value = value;
            this.expiresAtMillis = System.currentTimeMillis() + Math.max(1, ttlSeconds) * 1000;
        }

        T value() {
            return value;
        }

        boolean expired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }
}
