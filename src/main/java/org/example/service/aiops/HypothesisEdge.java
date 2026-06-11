package org.example.service.aiops;

public class HypothesisEdge {
    private final String from;
    private final String to;
    private final EdgeRelation relation;
    private final double weight;

    public HypothesisEdge(String from, String to, EdgeRelation relation, double weight) {
        this.from = from;
        this.to = to;
        this.relation = relation;
        this.weight = weight;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }

    public EdgeRelation getRelation() {
        return relation;
    }

    public double getWeight() {
        return weight;
    }
}
