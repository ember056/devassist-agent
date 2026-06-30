package org.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.service.TrustedRagRetrievalService;
import org.example.service.VectorSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 内部文档查询工具
 * 使用 RAG (Retrieval-Augmented Generation) 从内部知识库检索相关文档
 */
@Component
public class InternalDocsTools {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";
    
    private final TrustedRagRetrievalService trustedRagRetrievalService;
    
    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    /**
     * 构造函数注入依赖
     * Spring 会自动注入可信 RAG 检索编排服务
     */
    @Autowired
    public InternalDocsTools(
            TrustedRagRetrievalService trustedRagRetrievalService
    ) {
        this.trustedRagRetrievalService = trustedRagRetrievalService;
    }
    
    /**
     * 查询内部文档工具
     *
     * @param query 搜索查询，描述您要查找的信息
     * @return JSON 格式的搜索结果，包含相关文档内容、相似度分数和元数据
     */
    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(
            @ToolParam(description = "Search query describing what information you are looking for") 
            String query) {
        

        try {
            // 使用可信 RAG 检索编排：query rewrite 保护 + 向量召回 + 轻量 rerank
            TrustedRagRetrievalService.TrustedRagResult trustedRagResult =
                    trustedRagRetrievalService.retrieve(query, topK);
            List<VectorSearchService.SearchResult> searchResults = trustedRagResult.getResults();
            
            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }
            
            // 将搜索结果转换为 JSON 格式
            String resultJson = objectMapper.writeValueAsString(new TrustedToolResult(
                    "success",
                    "Answer only with facts supported by results.content. Cite metadata._file_name. Do not invent commands, metrics, thresholds, tools, or document names.",
                    trustedRagResult.getPreprocess().originalQuery(),
                    trustedRagResult.getPreprocess().finalQuery(),
                    trustedRagResult.getPreprocess().rewrittenQuery(),
                    trustedRagResult.getPreprocess().similarity(),
                    trustedRagResult.getPreprocess().rewriteAccepted(),
                    trustedRagResult.getRoute().getComplexity().name(),
                    trustedRagResult.getRoute().getRetrievalMode().name(),
                    trustedRagResult.isRerankApplied(),
                    searchResults.stream()
                            .map(InternalDocsTools::sourceName)
                            .distinct()
                            .collect(Collectors.toList()),
                    searchResults
            ));
            

            return resultJson;
            
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}", 
                    e.getMessage());
        }
    }

    private record TrustedToolResult(
            String status,
            String answerRules,
            String originalQuery,
            String finalQuery,
            String rewrittenQuery,
            double rewriteSimilarity,
            boolean rewriteAccepted,
            String queryComplexity,
            String retrievalMode,
            boolean rerankApplied,
            List<String> sourceFiles,
            List<VectorSearchService.SearchResult> results
    ) {
    }

    private static String sourceName(VectorSearchService.SearchResult result) {
        String metadata = result.getMetadata();
        if (metadata == null) {
            return "unknown";
        }
        int key = metadata.indexOf("\"_file_name\"");
        if (key < 0) {
            return metadata;
        }
        int colon = metadata.indexOf(':', key);
        int firstQuote = metadata.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : metadata.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return metadata;
        }
        return metadata.substring(firstQuote + 1, secondQuote);
    }
}
