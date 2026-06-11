package org.example.service.aiops;

public enum EvidenceStrength {
    STRONG_SUPPORT(10.0, "strong support"),
    MEDIUM_SUPPORT(3.0, "medium support"),
    WEAK_SUPPORT(1.5, "weak support"),
    NEUTRAL(1.0, "neutral"),
    WEAK_CONTRADICTION(0.7, "weak contradiction"),
    MEDIUM_CONTRADICTION(0.3, "medium contradiction"),
    STRONG_CONTRADICTION(0.1, "strong contradiction");

    private final double likelihoodRatio;
    private final String label;

    EvidenceStrength(double likelihoodRatio, String label) {
        this.likelihoodRatio = likelihoodRatio;
        this.label = label;
    }

    public double likelihoodRatio() {
        return likelihoodRatio;
    }

    public String label() {
        return label;
    }

    public boolean supportive() {
        return likelihoodRatio > 1.0;
    }

    public boolean contradictory() {
        return likelihoodRatio < 1.0;
    }
}
