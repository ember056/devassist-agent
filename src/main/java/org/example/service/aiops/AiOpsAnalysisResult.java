package org.example.service.aiops;

public class AiOpsAnalysisResult {
    private final HypothesisGraph graph;
    private final String report;

    public AiOpsAnalysisResult(HypothesisGraph graph, String report) {
        this.graph = graph;
        this.report = report;
    }

    public HypothesisGraph getGraph() {
        return graph;
    }

    public String getReport() {
        return report;
    }
}
