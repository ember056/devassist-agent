package org.example.service.aiops;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class AiOpsHypothesisGraphService {
    private static final int TOP_K = 4;

    public HypothesisGraph createInitialGraph(String incidentRequest) {
        HypothesisGraph graph = new HypothesisGraph();

        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_APP_ERROR,
                "Application error or business logic failure",
                HypothesisType.APP,
                prior(incidentRequest, "500", "error", "service unavailable", 0.24, 0.18)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_CPU_BOTTLENECK,
                "CPU resource bottleneck",
                HypothesisType.RESOURCE,
                prior(incidentRequest, "cpu", "highcpu", "load", 0.22, 0.12)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_MEMORY_PRESSURE,
                "Memory pressure or OOM risk",
                HypothesisType.RESOURCE,
                prior(incidentRequest, "memory", "oom", "jvm", 0.20, 0.10)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_DISK_PRESSURE,
                "Disk pressure or filesystem full",
                HypothesisType.RESOURCE,
                prior(incidentRequest, "disk", "filesystem", "storage", 0.18, 0.08)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_DB_BOTTLENECK,
                "Database performance bottleneck",
                HypothesisType.DEPENDENCY,
                prior(incidentRequest, "slow", "database", "db", 0.26, 0.16)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_DB_CONNECTION_POOL,
                "Database connection pool exhaustion",
                HypothesisType.CONFIG,
                prior(incidentRequest, "connection", "pool", "timeout", 0.22, 0.12)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_DOWNSTREAM_DEPENDENCY,
                "Downstream dependency failure",
                HypothesisType.DEPENDENCY,
                prior(incidentRequest, "downstream", "redis", "mq", 0.24, 0.14)
        ));
        graph.addHypothesis(new HypothesisNode(
                AiOpsEvidenceRuleService.H_RUNTIME_RESTART,
                "Runtime restart, crash, or container instability",
                HypothesisType.INFRA,
                prior(incidentRequest, "restart", "crash", "pod", 0.18, 0.08)
        ));

        graph.addEdge(AiOpsEvidenceRuleService.H_DB_BOTTLENECK, AiOpsEvidenceRuleService.H_DB_CONNECTION_POOL, EdgeRelation.CAUSES, 0.65);
        graph.addEdge(AiOpsEvidenceRuleService.H_DOWNSTREAM_DEPENDENCY, AiOpsEvidenceRuleService.H_APP_ERROR, EdgeRelation.CAUSES, 0.55);
        graph.addEdge(AiOpsEvidenceRuleService.H_RUNTIME_RESTART, AiOpsEvidenceRuleService.H_APP_ERROR, EdgeRelation.CAUSES, 0.50);
        graph.addEdge(AiOpsEvidenceRuleService.H_CPU_BOTTLENECK, AiOpsEvidenceRuleService.H_APP_ERROR, EdgeRelation.CAUSES, 0.40);
        graph.addEdge(AiOpsEvidenceRuleService.H_MEMORY_PRESSURE, AiOpsEvidenceRuleService.H_RUNTIME_RESTART, EdgeRelation.CAUSES, 0.45);

        return graph;
    }

    public void applyEvidence(HypothesisGraph graph, EvidenceNode evidence, List<EvidenceMatch> matches) {
        graph.addEvidence(evidence);
        for (EvidenceMatch match : matches) {
            graph.hypothesis(match.getHypothesisId())
                    .ifPresent(node -> node.updateConfidence(evidence, match.getStrength(), match.getReason()));
        }
    }

    public void prune(HypothesisGraph graph) {
        List<HypothesisNode> ranked = graph.rankedHypotheses();
        double bestConfidence = ranked.isEmpty() ? 0.0 : ranked.get(0).getCurrentConfidence();

        for (int i = 0; i < ranked.size(); i++) {
            HypothesisNode node = ranked.get(i);
            if (node.getStatus() == HypothesisStatus.REJECTED) {
                continue;
            }
            if (i >= TOP_K && node.getConfidenceHistory().isEmpty()) {
                node.prune("outside Top-" + TOP_K + " and no direct supporting evidence");
            } else if (bestConfidence >= 0.75
                    && bestConfidence - node.getCurrentConfidence() >= 0.45
                    && node.getConfidenceHistory().stream().noneMatch(update -> update.getStrength().supportive())) {
                node.prune("far below best hypothesis and has no supporting evidence");
            }
        }

        graph.bestActiveHypothesis().ifPresent(best -> {
            long strongSupportCount = best.getConfidenceHistory().stream()
                    .filter(update -> update.getStrength() == EvidenceStrength.STRONG_SUPPORT)
                    .count();
            if (best.getCurrentConfidence() >= 0.80 && strongSupportCount > 0) {
                best.confirm();
            }
        });
    }

    private double prior(String incidentRequest, String tokenA, String tokenB, String tokenC, double matched, double fallback) {
        String normalized = incidentRequest == null ? "" : incidentRequest.toLowerCase();
        if (normalized.contains(tokenA) || normalized.contains(tokenB) || normalized.contains(tokenC)) {
            return matched;
        }
        return fallback;
    }
}
