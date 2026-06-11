package org.example.service.aiops;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class HypothesisNode {
    private final String id;
    private final String name;
    private final HypothesisType type;
    private final double priorConfidence;
    private double currentConfidence;
    private HypothesisStatus status = HypothesisStatus.ACTIVE;
    private String pruneReason;
    private final Set<String> parentIds = new LinkedHashSet<>();
    private final Set<String> childIds = new LinkedHashSet<>();
    private final Set<String> evidenceIds = new LinkedHashSet<>();
    private final List<ConfidenceUpdate> confidenceHistory = new ArrayList<>();

    public HypothesisNode(String id, String name, HypothesisType type, double priorConfidence) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.priorConfidence = priorConfidence;
        this.currentConfidence = priorConfidence;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public HypothesisType getType() {
        return type;
    }

    public double getPriorConfidence() {
        return priorConfidence;
    }

    public double getCurrentConfidence() {
        return currentConfidence;
    }

    public HypothesisStatus getStatus() {
        return status;
    }

    public String getPruneReason() {
        return pruneReason;
    }

    public Set<String> getParentIds() {
        return parentIds;
    }

    public Set<String> getChildIds() {
        return childIds;
    }

    public Set<String> getEvidenceIds() {
        return evidenceIds;
    }

    public List<ConfidenceUpdate> getConfidenceHistory() {
        return confidenceHistory;
    }

    public void addParent(String parentId) {
        parentIds.add(parentId);
    }

    public void addChild(String childId) {
        childIds.add(childId);
    }

    public void addEvidence(String evidenceId) {
        evidenceIds.add(evidenceId);
    }

    public void updateConfidence(EvidenceNode evidence, EvidenceStrength strength, String reason) {
        double before = currentConfidence;
        double oldOdds = toOdds(before);
        double newOdds = oldOdds * strength.likelihoodRatio();
        currentConfidence = clamp(toProbability(newOdds));
        confidenceHistory.add(new ConfidenceUpdate(
                evidence.getId(),
                before,
                currentConfidence,
                strength.likelihoodRatio(),
                strength,
                reason
        ));
        addEvidence(evidence.getId());
        evidence.relateTo(id);

        if (strength == EvidenceStrength.STRONG_CONTRADICTION) {
            status = HypothesisStatus.REJECTED;
            pruneReason = "rejected by strong contradiction: " + reason;
        }
    }

    public void prune(String reason) {
        if (status == HypothesisStatus.ACTIVE) {
            status = HypothesisStatus.PRUNED;
            pruneReason = reason;
        }
    }

    public void confirm() {
        status = HypothesisStatus.CONFIRMED;
    }

    private double toOdds(double probability) {
        double safe = Math.max(0.001, Math.min(0.999, probability));
        return safe / (1.0 - safe);
    }

    private double toProbability(double odds) {
        return odds / (1.0 + odds);
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
