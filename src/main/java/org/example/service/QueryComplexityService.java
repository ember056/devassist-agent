package org.example.service;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 查询复杂度路由。
 * 简单问题直接召回，复杂问题召回候选片段后再进入 rerank。
 */
@Service
public class QueryComplexityService {

    private static final List<String> COMPLEX_KEYWORDS = List.of(
            "为什么", "原因", "根因", "分析", "排查", "方案", "步骤", "影响", "优化",
            "对比", "区别", "结合", "多个", "链路", "日志", "指标", "告警", "历史",
            "怎么处理", "如何解决", "怎么办", "原理", "流程"
    );

    @Value("${rag.retrieval.complex-min-length:18}")
    private int complexMinLength;

    @Value("${rag.retrieval.simple-mode:HYBRID}")
    private String simpleMode;

    @Value("${rag.retrieval.complex-mode:HYBRID}")
    private String complexMode;

    public QueryRoute route(String query) {
        boolean complex = isComplex(query);
        RetrievalMode mode = RetrievalMode.from(complex ? complexMode : simpleMode);
        return new QueryRoute(complex ? QueryComplexity.COMPLEX : QueryComplexity.SIMPLE, mode);
    }

    private boolean isComplex(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }

        String normalized = query.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= complexMinLength) {
            return true;
        }

        for (String keyword : COMPLEX_KEYWORDS) {
            if (normalized.contains(keyword)) {
                return true;
            }
        }

        int separatorCount = 0;
        for (char c : normalized.toCharArray()) {
            if (c == '，' || c == ',' || c == '；' || c == ';' || c == '、' || c == '?' || c == '？') {
                separatorCount++;
            }
        }
        return separatorCount >= 2;
    }

    public enum QueryComplexity {
        SIMPLE,
        COMPLEX
    }

    public enum RetrievalMode {
        VECTOR,
        BM25,
        HYBRID;

        public static RetrievalMode from(String value) {
            if (value == null || value.isBlank()) {
                return HYBRID;
            }
            try {
                return RetrievalMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return HYBRID;
            }
        }
    }

    @Getter
    public static class QueryRoute {
        private final QueryComplexity complexity;
        private final RetrievalMode retrievalMode;

        public QueryRoute(QueryComplexity complexity, RetrievalMode retrievalMode) {
            this.complexity = complexity;
            this.retrievalMode = retrievalMode;
        }

        public boolean complex() {
            return complexity == QueryComplexity.COMPLEX;
        }
    }
}
