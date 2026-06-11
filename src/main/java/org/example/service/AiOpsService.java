package org.example.service;

import org.example.service.aiops.AiOpsAnalysisResult;
import org.example.service.aiops.AiOpsHypothesisAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiOpsService {
    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    private final AiOpsHypothesisAnalysisService hypothesisAnalysisService;

    public AiOpsService(AiOpsHypothesisAnalysisService hypothesisAnalysisService) {
        this.hypothesisAnalysisService = hypothesisAnalysisService;
    }

    public AiOpsAnalysisResult executeAiOpsAnalysis(String incidentRequest) {
        String request = incidentRequest == null || incidentRequest.isBlank()
                ? "Analyze current active production alerts and identify the most likely root cause."
                : incidentRequest;
        logger.info("Starting AIOps hypothesis graph analysis. request={}", request);
        return hypothesisAnalysisService.analyze(request);
    }
}
