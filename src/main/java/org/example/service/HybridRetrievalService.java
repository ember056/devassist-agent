package org.example.service;

import org.example.service.QueryComplexityService.RetrievalMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 混合召回服务。
 * 支持纯向量、纯 BM25、向量 + BM25 融合三种模式。
 */
@Service
public class HybridRetrievalService {

    @Autowired
    private VectorSearchService vectorSearchService;

    @Autowired
    private KeywordSearchService keywordSearchService;

    @Value("${rag.retrieval.candidate-multiplier:3}")
    private int candidateMultiplier;

    @Value("${rag.retrieval.vector-weight:0.65}")
    private double vectorWeight;

    @Value("${rag.retrieval.bm25-weight:0.35}")
    private double bm25Weight;

    public List<VectorSearchService.SearchResult> retrieve(String query, int topK, RetrievalMode mode) {
        int candidateK = Math.max(topK, topK * Math.max(1, candidateMultiplier));

        return switch (mode) {
            case VECTOR -> markVector(vectorSearchService.searchSimilarDocuments(query, topK));
            case BM25 -> markBm25(keywordSearchService.search(query, topK));
            case HYBRID -> hybrid(query, topK, candidateK);
        };
    }

    public List<VectorSearchService.SearchResult> retrieve(
            String query,
            String lexicalFallbackQuery,
            int topK,
            RetrievalMode mode
    ) {
        if (lexicalFallbackQuery == null || lexicalFallbackQuery.isBlank()
                || lexicalFallbackQuery.equalsIgnoreCase(query)) {
            return retrieve(query, topK, mode);
        }

        int candidateK = Math.max(topK, topK * Math.max(1, candidateMultiplier));
        if (mode == RetrievalMode.VECTOR) {
            return mergeById(
                    markVector(vectorSearchService.searchSimilarDocuments(query, candidateK)),
                    markBm25(keywordSearchService.search(lexicalFallbackQuery, candidateK))
            ).stream()
                    .limit(candidateK)
                    .toList();
        }
        if (mode == RetrievalMode.BM25) {
            return mergeById(
                    markBm25(keywordSearchService.search(query, candidateK)),
                    markBm25(keywordSearchService.search(lexicalFallbackQuery, candidateK))
            ).stream()
                    .limit(candidateK)
                    .toList();
        }
        return hybrid(query, lexicalFallbackQuery, candidateK);
    }

    private List<VectorSearchService.SearchResult> hybrid(String query, int topK, int candidateK) {
        List<VectorSearchService.SearchResult> vectorResults =
                vectorSearchService.searchSimilarDocuments(query, candidateK);
        List<VectorSearchService.SearchResult> bm25Results =
                keywordSearchService.search(query, candidateK);

        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        Map<String, Double> vectorScores = normalizeVectorScores(vectorResults);
        Map<String, Double> bm25Scores = normalizeBm25Scores(bm25Results);

        for (VectorSearchService.SearchResult result : vectorResults) {
            result.setRetrievalMode("VECTOR");
            merged.put(result.getId(), result);
        }

        for (VectorSearchService.SearchResult result : bm25Results) {
            VectorSearchService.SearchResult existing = merged.get(result.getId());
            if (existing == null) {
                result.setRetrievalMode("BM25");
                merged.put(result.getId(), result);
            } else {
                existing.setBm25Score(result.getBm25Score());
                existing.setRetrievalMode("HYBRID");
            }
        }

        List<VectorSearchService.SearchResult> finalResults = new ArrayList<>(merged.values());
        for (VectorSearchService.SearchResult result : finalResults) {
            double vectorScore = vectorScores.getOrDefault(result.getId(), 0.0);
            double bm25Score = bm25Scores.getOrDefault(result.getId(), 0.0);
            result.setHybridScore(vectorWeight * vectorScore + bm25Weight * bm25Score);
        }

        return finalResults.stream()
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getHybridScore).reversed())
                .limit(candidateK)
                .toList();
    }

    private List<VectorSearchService.SearchResult> hybrid(String query, String lexicalFallbackQuery, int candidateK) {
        List<VectorSearchService.SearchResult> vectorResults =
                vectorSearchService.searchSimilarDocuments(query, candidateK);
        List<VectorSearchService.SearchResult> bm25Results =
                mergeById(
                        keywordSearchService.search(query, candidateK),
                        keywordSearchService.search(lexicalFallbackQuery, candidateK)
                );

        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        Map<String, Double> vectorScores = normalizeVectorScores(vectorResults);
        Map<String, Double> bm25Scores = normalizeBm25Scores(bm25Results);

        for (VectorSearchService.SearchResult result : vectorResults) {
            result.setRetrievalMode("VECTOR");
            merged.put(result.getId(), result);
        }

        for (VectorSearchService.SearchResult result : bm25Results) {
            VectorSearchService.SearchResult existing = merged.get(result.getId());
            if (existing == null) {
                result.setRetrievalMode("BM25");
                merged.put(result.getId(), result);
            } else {
                existing.setBm25Score(result.getBm25Score());
                existing.setRetrievalMode("HYBRID");
            }
        }

        List<VectorSearchService.SearchResult> finalResults = new ArrayList<>(merged.values());
        for (VectorSearchService.SearchResult result : finalResults) {
            double vectorScore = vectorScores.getOrDefault(result.getId(), 0.0);
            double bm25Score = bm25Scores.getOrDefault(result.getId(), 0.0);
            result.setHybridScore(vectorWeight * vectorScore + bm25Weight * bm25Score);
        }

        return finalResults.stream()
                .sorted(Comparator.comparing(VectorSearchService.SearchResult::getHybridScore).reversed())
                .limit(candidateK)
                .toList();
    }

    @SafeVarargs
    private final List<VectorSearchService.SearchResult> mergeById(List<VectorSearchService.SearchResult>... lists) {
        Map<String, VectorSearchService.SearchResult> merged = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<VectorSearchService.SearchResult> list : lists) {
            for (VectorSearchService.SearchResult result : list) {
                if (seen.add(result.getId())) {
                    merged.put(result.getId(), result);
                } else {
                    VectorSearchService.SearchResult existing = merged.get(result.getId());
                    if (result.getBm25Score() != null) {
                        existing.setBm25Score(Math.max(
                                existing.getBm25Score() == null ? 0.0 : existing.getBm25Score(),
                                result.getBm25Score()
                        ));
                    }
                }
            }
        }
        return new ArrayList<>(merged.values());
    }

    private List<VectorSearchService.SearchResult> markVector(List<VectorSearchService.SearchResult> results) {
        results.forEach(result -> result.setRetrievalMode("VECTOR"));
        return results;
    }

    private List<VectorSearchService.SearchResult> markBm25(List<VectorSearchService.SearchResult> results) {
        results.forEach(result -> result.setRetrievalMode("BM25"));
        return results;
    }

    private Map<String, Double> normalizeVectorScores(List<VectorSearchService.SearchResult> results) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        for (VectorSearchService.SearchResult result : results) {
            normalized.put(result.getId(), 1.0 / (1.0 + Math.max(0.0, result.getScore())));
        }
        return normalized;
    }

    private Map<String, Double> normalizeBm25Scores(List<VectorSearchService.SearchResult> results) {
        Map<String, Double> normalized = new LinkedHashMap<>();
        double max = results.stream()
                .map(VectorSearchService.SearchResult::getBm25Score)
                .filter(score -> score != null && score > 0)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        if (max == 0.0) {
            return normalized;
        }

        for (VectorSearchService.SearchResult result : results) {
            normalized.put(result.getId(), Math.max(0.0, result.getBm25Score()) / max);
        }
        return normalized;
    }
}
