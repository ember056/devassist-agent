package org.example.service.aiops;

import org.example.trace.AgentTraceService;
import org.example.trace.TraceSpan;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AiOpsHypothesisAnalysisService {
    private final AiOpsHypothesisGraphService graphService;
    private final AiOpsEvidenceCollectorService evidenceCollectorService;
    private final AiOpsEvidenceRuleService evidenceRuleService;
    private final AiOpsReportService reportService;
    private final AgentTraceService traceService;

    public AiOpsHypothesisAnalysisService(
            AiOpsHypothesisGraphService graphService,
            AiOpsEvidenceCollectorService evidenceCollectorService,
            AiOpsEvidenceRuleService evidenceRuleService,
            AiOpsReportService reportService,
            AgentTraceService traceService
    ) {
        this.graphService = graphService;
        this.evidenceCollectorService = evidenceCollectorService;
        this.evidenceRuleService = evidenceRuleService;
        this.reportService = reportService;
        this.traceService = traceService;
    }

    public AiOpsAnalysisResult analyze(String incidentRequest) {
        HypothesisGraph graph;
        try (TraceSpan span = traceService.startSpan("aiops", "create_initial_graph", null)) {
            graph = graphService.createInitialGraph(incidentRequest);
            span.success(Map.of(
                    "hypothesisCount", graph.hypotheses().size(),
                    "edgeCount", graph.edges().size()
            ));
        }

        List<EvidenceNode> evidenceNodes;
        try (TraceSpan span = traceService.startSpan("aiops", "collect_evidence", null)) {
            evidenceNodes = evidenceCollectorService.collect(incidentRequest);
            span.success(Map.of("evidenceCount", evidenceNodes.size()));
        }

        for (EvidenceNode evidence : evidenceNodes) {
            List<EvidenceMatch> matches;
            try (TraceSpan span = traceService.startSpan("aiops", "match_evidence", Map.of(
                    "evidenceId", evidence.getId(),
                    "source", evidence.getSource(),
                    "type", evidence.getType().name()
            ))) {
                matches = evidenceRuleService.match(evidence);
                span.success(Map.of("matchCount", matches.size()));
            }
            try (TraceSpan span = traceService.startSpan("aiops", "apply_evidence", Map.of("evidenceId", evidence.getId()))) {
                graphService.applyEvidence(graph, evidence, matches);
                span.success(Map.of("relatedHypothesisCount", evidence.getRelatedHypothesisIds().size()));
            }
        }

        try (TraceSpan span = traceService.startSpan("aiops", "prune_graph", null)) {
            graphService.prune(graph);
            long activeCount = graph.hypotheses().stream()
                    .filter(node -> node.getStatus() == HypothesisStatus.ACTIVE
                            || node.getStatus() == HypothesisStatus.CONFIRMED)
                    .count();
            span.success(Map.of("activeHypothesisCount", activeCount));
        }

        String report;
        try (TraceSpan span = traceService.startSpan("aiops", "build_report", null)) {
            report = reportService.buildReport(incidentRequest, graph);
            span.success(Map.of("reportLength", report.length()));
        }
        return new AiOpsAnalysisResult(graph, report);
    }
}
