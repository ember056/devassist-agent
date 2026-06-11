package org.example.service.aiops;

import java.time.Instant;

public class ConfidenceUpdate {
    private final String evidenceId;
    private final double before;
    private final double after;
    private final double likelihoodRatio;
    private final EvidenceStrength strength;
    private final String reason;
    private final Instant timestamp;

    public ConfidenceUpdate(
            String evidenceId,
            double before,
            double after,
            double likelihoodRatio,
            EvidenceStrength strength,
            String reason
    ) {
        this.evidenceId = evidenceId;
        this.before = before;
        this.after = after;
        this.likelihoodRatio = likelihoodRatio;
        this.strength = strength;
        this.reason = reason;
        this.timestamp = Instant.now();
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public double getBefore() {
        return before;
    }

    public double getAfter() {
        return after;
    }

    public double getLikelihoodRatio() {
        return likelihoodRatio;
    }

    public EvidenceStrength getStrength() {
        return strength;
    }

    public String getReason() {
        return reason;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
