package org.example.service.aiops;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiOpsHypothesisAnalysisService {
    private final AiOpsHypothesisGraphService graphService;
    private final AiOpsEvidenceCollectorService evidenceCollectorService;
    private final AiOpsEvidenceRuleService evidenceRuleService;
    private final AiOpsReportService reportService;

    public AiOpsHypothesisAnalysisService(
            AiOpsHypothesisGraphService graphService,
            AiOpsEvidenceCollectorService evidenceCollectorService,
            AiOpsEvidenceRuleService evidenceRuleService,
            AiOpsReportService reportService
    ) {
        this.graphService = graphService;
        this.evidenceCollectorService = evidenceCollectorService;
        this.evidenceRuleService = evidenceRuleService;
        this.reportService = reportService;
    }

    public AiOpsAnalysisResult analyze(String incidentRequest) {
        HypothesisGraph graph = graphService.createInitialGraph(incidentRequest);
        List<EvidenceNode> evidenceNodes = evidenceCollectorService.collect(incidentRequest);

        for (EvidenceNode evidence : evidenceNodes) {
            List<EvidenceMatch> matches = evidenceRuleService.match(evidence);
            graphService.applyEvidence(graph, evidence, matches);
        }

        graphService.prune(graph);
        String report = reportService.buildReport(incidentRequest, graph);
        return new AiOpsAnalysisResult(graph, report);
    }
}
