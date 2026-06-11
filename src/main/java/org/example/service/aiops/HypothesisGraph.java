package org.example.service.aiops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class HypothesisGraph {
    private final Map<String, HypothesisNode> hypotheses = new LinkedHashMap<>();
    private final Map<String, EvidenceNode> evidence = new LinkedHashMap<>();
    private final List<HypothesisEdge> edges = new ArrayList<>();

    public void addHypothesis(HypothesisNode node) {
        hypotheses.put(node.getId(), node);
    }

    public void addEvidence(EvidenceNode node) {
        evidence.put(node.getId(), node);
    }

    public void addEdge(String from, String to, EdgeRelation relation, double weight) {
        edges.add(new HypothesisEdge(from, to, relation, weight));
        HypothesisNode fromNode = hypotheses.get(from);
        HypothesisNode toNode = hypotheses.get(to);
        if (fromNode != null && toNode != null) {
            fromNode.addChild(to);
            toNode.addParent(from);
        }
    }

    public Optional<HypothesisNode> hypothesis(String id) {
        return Optional.ofNullable(hypotheses.get(id));
    }

    public Optional<EvidenceNode> evidence(String id) {
        return Optional.ofNullable(evidence.get(id));
    }

    public List<HypothesisNode> hypotheses() {
        return new ArrayList<>(hypotheses.values());
    }

    public List<EvidenceNode> evidence() {
        return new ArrayList<>(evidence.values());
    }

    public List<HypothesisEdge> edges() {
        return edges;
    }

    public List<HypothesisNode> rankedHypotheses() {
        return hypotheses.values().stream()
                .sorted(Comparator.comparingDouble(HypothesisNode::getCurrentConfidence).reversed())
                .toList();
    }

    public Optional<HypothesisNode> bestActiveHypothesis() {
        return hypotheses.values().stream()
                .filter(node -> node.getStatus() == HypothesisStatus.ACTIVE
                        || node.getStatus() == HypothesisStatus.CONFIRMED)
                .max(Comparator.comparingDouble(HypothesisNode::getCurrentConfidence));
    }
}
