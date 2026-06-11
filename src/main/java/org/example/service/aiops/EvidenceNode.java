package org.example.service.aiops;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

public class EvidenceNode {
    private final String id;
    private final EvidenceType type;
    private final String source;
    private final String summary;
    private final String content;
    private final Instant createdAt;
    private final Set<String> relatedHypothesisIds = new LinkedHashSet<>();

    public EvidenceNode(String id, EvidenceType type, String source, String summary, String content) {
        this.id = id;
        this.type = type;
        this.source = source;
        this.summary = summary;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public EvidenceType getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public String getSummary() {
        return summary;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Set<String> getRelatedHypothesisIds() {
        return relatedHypothesisIds;
    }

    public void relateTo(String hypothesisId) {
        relatedHypothesisIds.add(hypothesisId);
    }
}
