package org.example.service.aiops;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class AiOpsReportService {
    public String buildReport(String incidentRequest, HypothesisGraph graph) {
        List<HypothesisNode> ranked = graph.rankedHypotheses();
        HypothesisNode best = ranked.isEmpty() ? null : ranked.get(0);

        StringBuilder report = new StringBuilder();
        report.append("# AIOps Hypothesis Graph Analysis Report\n\n");
        report.append("## Incident\n\n");
        report.append(blankToDefault(incidentRequest, "Analyze the current active production alerts.")).append("\n\n");

        report.append("## Most Likely Root Cause\n\n");
        if (best == null) {
            report.append("No hypothesis was generated.\n\n");
        } else {
            report.append("- Root cause: ").append(best.getName()).append("\n");
            report.append("- Posterior confidence: ").append(percent(best.getCurrentConfidence())).append("\n");
            report.append("- Status: ").append(best.getStatus()).append("\n");
            report.append("- Prior confidence: ").append(percent(best.getPriorConfidence())).append("\n\n");
        }

        report.append("## Hypothesis Ranking\n\n");
        report.append("| Rank | Hypothesis | Type | Prior | Posterior | Status |\n");
        report.append("|---:|---|---|---:|---:|---|\n");
        for (int i = 0; i < ranked.size(); i++) {
            HypothesisNode node = ranked.get(i);
            report.append("| ").append(i + 1)
                    .append(" | ").append(node.getName())
                    .append(" | ").append(node.getType())
                    .append(" | ").append(percent(node.getPriorConfidence()))
                    .append(" | ").append(percent(node.getCurrentConfidence()))
                    .append(" | ").append(node.getStatus());
            if (node.getPruneReason() != null) {
                report.append(": ").append(node.getPruneReason());
            }
            report.append(" |\n");
        }
        report.append("\n");

        if (best != null) {
            appendExplanationTree(report, best, graph);
        }

        appendEvidenceTrace(report, graph);
        appendRecommendations(report, best);
        return report.toString();
    }

    private void appendExplanationTree(StringBuilder report, HypothesisNode best, HypothesisGraph graph) {
        report.append("## Evidence-backed Explanation Tree\n\n");
        report.append("- Incident symptom\n");
        report.append("  - ").append(best.getName())
                .append(" (").append(percent(best.getCurrentConfidence())).append(")\n");
        for (String evidenceId : best.getEvidenceIds()) {
            graph.evidence(evidenceId).ifPresent(evidence ->
                    report.append("    - Evidence [").append(evidence.getSource()).append("]: ")
                            .append(evidence.getSummary()).append("\n"));
        }
        report.append("\n");

        report.append("## Confidence Update Trace\n\n");
        if (best.getConfidenceHistory().isEmpty()) {
            report.append("No direct evidence updated the leading hypothesis; ranking is based on priors and related evidence.\n\n");
            return;
        }
        report.append("| Evidence | Strength | LR | Before | After | Reason |\n");
        report.append("|---|---|---:|---:|---:|---|\n");
        for (ConfidenceUpdate update : best.getConfidenceHistory()) {
            report.append("| ").append(update.getEvidenceId())
                    .append(" | ").append(update.getStrength().label())
                    .append(" | ").append(format(update.getLikelihoodRatio()))
                    .append(" | ").append(percent(update.getBefore()))
                    .append(" | ").append(percent(update.getAfter()))
                    .append(" | ").append(update.getReason())
                    .append(" |\n");
        }
        report.append("\n");
    }

    private void appendEvidenceTrace(StringBuilder report, HypothesisGraph graph) {
        report.append("## Evidence Collected\n\n");
        report.append("| ID | Type | Source | Summary | Linked Hypotheses |\n");
        report.append("|---|---|---|---|---|\n");
        for (EvidenceNode evidence : graph.evidence()) {
            report.append("| ").append(evidence.getId())
                    .append(" | ").append(evidence.getType())
                    .append(" | ").append(evidence.getSource())
                    .append(" | ").append(evidenceSummary(evidence))
                    .append(" | ").append(String.join(", ", evidence.getRelatedHypothesisIds()))
                    .append(" |\n");
        }
        report.append("\n");
    }

    private String evidenceSummary(EvidenceNode evidence) {
        if (evidence.getType() != EvidenceType.RUNBOOK) {
            return evidence.getSummary();
        }
        String content = evidence.getContent();
        String sourceFiles = extractSourceFiles(content);
        if (sourceFiles.isBlank()) {
            return evidence.getSummary();
        }
        return evidence.getSummary() + " (" + sourceFiles + ")";
    }

    private String extractSourceFiles(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        int key = content.indexOf("\"sourceFiles\"");
        if (key < 0) {
            return "";
        }
        int start = content.indexOf('[', key);
        int end = start < 0 ? -1 : content.indexOf(']', start);
        if (start < 0 || end < 0) {
            return "";
        }
        return content.substring(start + 1, end)
                .replace("\"", "")
                .replace("\\", "")
                .trim();
    }

    private void appendRecommendations(StringBuilder report, HypothesisNode best) {
        report.append("## Recommended Next Actions\n\n");
        if (best == null) {
            report.append("1. Re-run evidence collection after metrics and logs are available.\n");
            return;
        }

        switch (best.getId()) {
            case AiOpsEvidenceRuleService.H_CPU_BOTTLENECK -> {
                report.append("1. Check top CPU threads and recent traffic spikes.\n");
                report.append("2. Consider horizontal scaling or rate limiting if user traffic is the driver.\n");
            }
            case AiOpsEvidenceRuleService.H_MEMORY_PRESSURE -> {
                report.append("1. Inspect heap usage, GC logs, and recent memory-heavy changes.\n");
                report.append("2. Prepare rollback or restart only after preserving diagnostics.\n");
            }
            case AiOpsEvidenceRuleService.H_DB_CONNECTION_POOL -> {
                report.append("1. Check connection pool active/waiting metrics and slow SQL.\n");
                report.append("2. Mitigate by reducing long queries, raising pool limits carefully, or scaling DB capacity.\n");
            }
            case AiOpsEvidenceRuleService.H_DB_BOTTLENECK -> {
                report.append("1. Review slow query samples and execution plans.\n");
                report.append("2. Add indexes or throttle expensive queries after confirming impact.\n");
            }
            case AiOpsEvidenceRuleService.H_DOWNSTREAM_DEPENDENCY -> {
                report.append("1. Check downstream service health, timeout rate, and circuit breaker state.\n");
                report.append("2. Enable fallback, retry budget controls, or dependency isolation if needed.\n");
            }
            case AiOpsEvidenceRuleService.H_RUNTIME_RESTART -> {
                report.append("1. Inspect pod restart reasons, container exit codes, and deployment events.\n");
                report.append("2. Roll back recent runtime or configuration changes if restarts align with deployment time.\n");
            }
            default -> {
                report.append("1. Inspect application error logs around the alert window.\n");
                report.append("2. Compare recent releases, config changes, and dependency health.\n");
            }
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100.0);
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
