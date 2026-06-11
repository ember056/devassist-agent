package org.example.service.aiops;

public class EvidenceMatch {
    private final String hypothesisId;
    private final EvidenceStrength strength;
    private final String reason;

    public EvidenceMatch(String hypothesisId, EvidenceStrength strength, String reason) {
        this.hypothesisId = hypothesisId;
        this.strength = strength;
        this.reason = reason;
    }

    public String getHypothesisId() {
        return hypothesisId;
    }

    public EvidenceStrength getStrength() {
        return strength;
    }

    public String getReason() {
        return reason;
    }
}
