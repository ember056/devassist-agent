package org.example.service;

import jakarta.annotation.PostConstruct;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 轻量 BM25 关键词检索。
 * 用于和 Milvus 向量召回组成混合召回，不依赖额外搜索中间件。
 */
@Service
public class KeywordSearchService {

    private static final Logger logger = LoggerFactory.getLogger(KeywordSearchService.class);
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Map<String, IndexedDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sourceToDocumentIds = new ConcurrentHashMap<>();

    @Autowired
    private DocumentChunkService chunkService;

    @Value("${file.upload.path}")
    private String uploadPath;

    @Value("${rag.bm25.bootstrap-enabled:true}")
    private boolean bootstrapEnabled;

    @PostConstruct
    public void bootstrapFromUploadDirectory() {
        if (!bootstrapEnabled) {
            return;
        }

        try {
            Path dirPath = Paths.get(uploadPath).normalize();
            File directory = dirPath.toFile();
            if (!directory.exists() || !directory.isDirectory()) {
                logger.info("BM25 启动加载跳过，上传目录不存在: {}", dirPath);
                return;
            }

            File[] files = directory.listFiles((dir, name) -> name.endsWith(".txt") || name.endsWith(".md"));
            if (files == null || files.length == 0) {
                return;
            }

            for (File file : files) {
                String content = Files.readString(file.toPath());
                List<DocumentChunk> chunks = chunkService.chunkDocument(content, file.getAbsolutePath());
                indexChunks(file.getAbsolutePath(), chunks);
            }
            logger.info("BM25 启动加载完成，文档片段数: {}", documents.size());
        } catch (Exception e) {
            logger.warn("BM25 启动加载失败，不影响向量检索: {}", e.getMessage());
        }
    }

    public void indexChunks(String sourcePath, List<DocumentChunk> chunks) {
        if (sourcePath == null || chunks == null || chunks.isEmpty()) {
            return;
        }

        String normalizedSource = normalizeSource(sourcePath);
        deleteSource(normalizedSource);

        Set<String> ids = new HashSet<>();
        for (DocumentChunk chunk : chunks) {
            String id = UUID.nameUUIDFromBytes((normalizedSource + "_" + chunk.getChunkIndex()).getBytes()).toString();
            IndexedDocument document = IndexedDocument.from(id, normalizedSource, chunk);
            documents.put(id, document);
            ids.add(id);
        }

        sourceToDocumentIds.put(normalizedSource, ids);
        logger.info("BM25 索引完成: source={}, chunks={}", normalizedSource, ids.size());
    }

    public void deleteSource(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return;
        }

        String normalizedSource = normalizeSource(sourcePath);
        Set<String> ids = sourceToDocumentIds.remove(normalizedSource);
        if (ids == null || ids.isEmpty()) {
            return;
        }

        for (String id : ids) {
            documents.remove(id);
        }
        logger.info("BM25 已删除旧索引: source={}, chunks={}", normalizedSource, ids.size());
    }

    public List<VectorSearchService.SearchResult> search(String query, int topK) {
        if (query == null || query.isBlank() || documents.isEmpty() || topK <= 0) {
            return List.of();
        }

        List<String> queryTerms = tokenize(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        double avgDocLength = documents.values().stream()
                .mapToInt(IndexedDocument::length)
                .average()
                .orElse(1.0);

        return documents.values().stream()
                .map(document -> toSearchResult(document, bm25(queryTerms, document, avgDocLength)))
                .filter(result -> result.getBm25Score() != null && result.getBm25Score() > 0)
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getBm25Score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    private VectorSearchService.SearchResult toSearchResult(IndexedDocument document, double score) {
        VectorSearchService.SearchResult result = new VectorSearchService.SearchResult();
        result.setId(document.id());
        result.setContent(document.content());
        result.setMetadata(document.metadata());
        result.setScore(0.0f);
        result.setBm25Score(score);
        result.setRetrievalMode("BM25");
        return result;
    }

    private double bm25(List<String> queryTerms, IndexedDocument document, double avgDocLength) {
        double score = 0.0;
        int totalDocs = documents.size();

        for (String term : queryTerms) {
            int tf = document.termFrequency().getOrDefault(term, 0);
            if (tf == 0) {
                continue;
            }

            long df = documents.values().stream()
                    .filter(doc -> doc.termFrequency().containsKey(term))
                    .count();
            double idf = Math.log(1 + (totalDocs - df + 0.5) / (df + 0.5));
            double denominator = tf + K1 * (1 - B + B * document.length() / avgDocLength);
            score += idf * (tf * (K1 + 1)) / denominator;
        }

        return score;
    }

    private String normalizeSource(String sourcePath) {
        return Paths.get(sourcePath).normalize().toString().replace(File.separator, "/");
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder han = new StringBuilder();

        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                flushAscii(ascii, tokens);
                han.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushHan(han, tokens);
                ascii.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushHan(han, tokens);
            }
        }

        flushAscii(ascii, tokens);
        flushHan(han, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder ascii, List<String> tokens) {
        if (ascii.length() >= 2) {
            tokens.add(ascii.toString());
        }
        ascii.setLength(0);
    }

    private static void flushHan(StringBuilder han, List<String> tokens) {
        if (han.length() == 1) {
            tokens.add(han.toString());
        } else if (han.length() > 1) {
            for (int i = 0; i < han.length() - 1; i++) {
                tokens.add(han.substring(i, i + 2));
            }
        }
        han.setLength(0);
    }

    private record IndexedDocument(
            String id,
            String source,
            String content,
            String metadata,
            Map<String, Integer> termFrequency,
            int length
    ) {
        static IndexedDocument from(String id, String source, DocumentChunk chunk) {
            List<String> terms = tokenize(chunk.getContent());
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : terms) {
                termFrequency.merge(term, 1, Integer::sum);
            }

            String metadata = "{\"_source\":\"%s\",\"chunkIndex\":%d,\"title\":\"%s\"}"
                    .formatted(source, chunk.getChunkIndex(), chunk.getTitle() == null ? "" : chunk.getTitle());

            return new IndexedDocument(
                    id,
                    source,
                    chunk.getContent(),
                    metadata,
                    termFrequency,
                    Math.max(1, terms.size())
            );
        }
    }
}
