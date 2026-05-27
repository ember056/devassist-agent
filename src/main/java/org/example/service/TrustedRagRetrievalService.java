package org.example.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 可信 RAG 检索编排。
 * 统一串起 query rewrite 质量过滤、向量召回和轻量 rerank。
 */
@Service
public class TrustedRagRetrievalService {

    @Autowired
    private QueryPreprocessService queryPreprocessService;

    @Autowired
    private HybridRetrievalService hybridRetrievalService;

    @Autowired
    private QueryComplexityService queryComplexityService;

    @Autowired
    private RerankService rerankService;

    public TrustedRagResult retrieve(String question, int topK) {
        QueryPreprocessService.QueryPreprocessResult preprocessResult =
                queryPreprocessService.preprocess(question);

        QueryComplexityService.QueryRoute route =
                queryComplexityService.route(preprocessResult.finalQuery());

        List<VectorSearchService.SearchResult> rawResults =
                hybridRetrievalService.retrieve(
                        preprocessResult.finalQuery(),
                        topK,
                        route.getRetrievalMode()
                );

        boolean rerankApplied = route.complex()
                && rerankService.shouldRerank(preprocessResult.finalQuery(), rawResults);
        List<VectorSearchService.SearchResult> finalResults =
                rerankApplied
                        ? rerankService.rerank(preprocessResult.finalQuery(), rawResults)
                        : rawResults;
        assignSourceIndexes(finalResults);

        return new TrustedRagResult(preprocessResult, finalResults, rerankApplied, route);
    }

    private void assignSourceIndexes(List<VectorSearchService.SearchResult> results) {
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setSourceIndex(i + 1);
        }
    }

    @Getter
    public static class TrustedRagResult {
        private final QueryPreprocessService.QueryPreprocessResult preprocess;
        private final List<VectorSearchService.SearchResult> results;
        private final boolean rerankApplied;
        private final QueryComplexityService.QueryRoute route;

        public TrustedRagResult(
                QueryPreprocessService.QueryPreprocessResult preprocess,
                List<VectorSearchService.SearchResult> results,
                boolean rerankApplied,
                QueryComplexityService.QueryRoute route
        ) {
            this.preprocess = preprocess;
            this.results = results;
            this.rerankApplied = rerankApplied;
            this.route = route;
        }
    }
}
