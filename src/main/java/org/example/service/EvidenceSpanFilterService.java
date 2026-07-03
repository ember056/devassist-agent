package org.example.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class EvidenceSpanFilterService {
    private static final double MIN_FILTER_SCORE = 0.38;
    private static final int MAX_SPANS_PER_TYPE = 8;

    public FilterResult filter(String question, List<EvidenceSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return new FilterResult(List.of(), List.of());
        }

        List<String> targetTerms = targetSectionTerms(question);
        Set<String> questionTerms = tokenize(question);
        List<ScoredSpan> scored = new ArrayList<>();
        List<ScoredSpan> dropped = new ArrayList<>();

        for (EvidenceSpan span : spans) {
            double lexical = lexicalOverlap(questionTerms, span);
            double section = sectionAffinity(targetTerms, span);
            double typePrior = typePrior(span.type());
            double score = span.supportScore() * 0.45 + lexical * 0.25 + section * 0.20 + typePrior * 0.10;
            ScoredSpan scoredSpan = new ScoredSpan(span, score, lexical, section);
            if (shouldKeep(scoredSpan, targetTerms)) {
                scored.add(scoredSpan);
            } else {
                dropped.add(scoredSpan);
            }
        }

        Map<EvidenceSpan.Type, Integer> typeCounts = new LinkedHashMap<>();
        List<EvidenceSpan> retained = scored.stream()
                .sorted(Comparator.comparing(ScoredSpan::filterScore).reversed())
                .filter(item -> incrementWithinLimit(typeCounts, item.span().type()))
                .map(ScoredSpan::span)
                .collect(Collectors.toList());

        retained = keepQuestionCriticalActions(question, spans, retained);
        return new FilterResult(retained, dropped);
    }

    private boolean shouldKeep(ScoredSpan scoredSpan, List<String> targetTerms) {
        EvidenceSpan span = scoredSpan.span();
        if (!targetTerms.isEmpty() && scoredSpan.sectionAffinity() <= 0.0) {
            return isGuardrail(span) && scoredSpan.filterScore() >= 0.30;
        }
        if (isGuardrail(span)) {
            return scoredSpan.filterScore() >= 0.30 || scoredSpan.sectionAffinity() > 0.0;
        }
        return scoredSpan.filterScore() >= MIN_FILTER_SCORE;
    }

    private boolean isGuardrail(EvidenceSpan span) {
        return span.type() == EvidenceSpan.Type.SAFETY || span.type() == EvidenceSpan.Type.VERIFICATION;
    }

    private List<EvidenceSpan> keepQuestionCriticalActions(
            String question,
            List<EvidenceSpan> allSpans,
            List<EvidenceSpan> retained
    ) {
        boolean hasAction = retained.stream().anyMatch(span -> span.type() == EvidenceSpan.Type.ACTION);
        if (hasAction) {
            return retained;
        }
        List<EvidenceSpan> fallbackActions = allSpans.stream()
                .filter(span -> span.type() == EvidenceSpan.Type.ACTION)
                .sorted(Comparator.comparing(EvidenceSpan::supportScore).reversed())
                .limit(3)
                .toList();
        if (fallbackActions.isEmpty()) {
            return retained;
        }
        List<EvidenceSpan> merged = new ArrayList<>(retained);
        merged.addAll(fallbackActions);
        return dedupe(merged);
    }

    private List<EvidenceSpan> dedupe(List<EvidenceSpan> spans) {
        Map<String, EvidenceSpan> deduped = new LinkedHashMap<>();
        for (EvidenceSpan span : spans) {
            deduped.putIfAbsent(span.type() + "|" + span.sourceFile() + "|" + span.headingPath() + "|" + span.normalizedText(), span);
        }
        return List.copyOf(deduped.values());
    }

    private boolean incrementWithinLimit(Map<EvidenceSpan.Type, Integer> typeCounts, EvidenceSpan.Type type) {
        int count = typeCounts.getOrDefault(type, 0);
        if (count >= MAX_SPANS_PER_TYPE) {
            return false;
        }
        typeCounts.put(type, count + 1);
        return true;
    }

    private double lexicalOverlap(Set<String> questionTerms, EvidenceSpan span) {
        if (questionTerms.isEmpty()) {
            return 0.0;
        }
        Set<String> spanTerms = tokenize(String.join(" ",
                span.text(),
                safe(span.headingPath()),
                safe(span.sectionTitle()),
                safe(span.sourceFile())
        ));
        if (spanTerms.isEmpty()) {
            return 0.0;
        }
        long hits = questionTerms.stream().filter(spanTerms::contains).count();
        return Math.min(1.0, hits / (double) Math.min(6, questionTerms.size()));
    }

    private double sectionAffinity(List<String> targetTerms, EvidenceSpan span) {
        if (targetTerms.isEmpty()) {
            return 0.5;
        }
        String searchable = String.join(" ",
                safe(span.headingPath()),
                safe(span.sectionTitle()),
                safe(span.text())
        ).toLowerCase(Locale.ROOT);
        for (String term : targetTerms) {
            if (searchable.contains(term)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double typePrior(EvidenceSpan.Type type) {
        return switch (type) {
            case ACTION -> 1.0;
            case EVIDENCE -> 0.9;
            case SAFETY -> 0.75;
            case VERIFICATION -> 0.7;
            case CONTEXT -> 0.45;
        };
    }

    private List<String> targetSectionTerms(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (normalized.contains("cache avalanche") || normalized.contains("ttl avalanche") || normalized.contains("many cache keys expired")) {
            return List.of("cache avalanche");
        }
        if (normalized.contains("big key") || normalized.contains("slow command") || normalized.contains("slowlog")) {
            return List.of("big key", "slow command");
        }
        if (normalized.contains("hot key")) {
            return List.of("hot key");
        }
        if (normalized.contains("connection leak")) {
            return List.of("connection leak");
        }
        if (normalized.contains("lock wait") || normalized.contains("slow query")) {
            return List.of("slow sql", "lock contention");
        }
        if (normalized.contains("poison message") || normalized.contains("schema mismatch")) {
            return List.of("poison message", "schema mismatch");
        }
        if (normalized.contains("downstream") || normalized.contains("consumer lag")) {
            return List.of("downstream dependency", "consumer lag", "backlog");
        }
        if (normalized.contains("liveness probe") || normalized.contains("readiness probe") || normalized.contains("probe")) {
            return List.of("probe misconfiguration", "liveness", "readiness");
        }
        if (normalized.contains("bad deployment") || normalized.contains("invalid config")) {
            return List.of("bad deployment", "invalid config");
        }
        if (normalized.contains("oomkilled") || normalized.contains("crashloopbackoff")) {
            return List.of("oomkilled", "crashloopbackoff", "pod restart");
        }
        return List.of();
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{IsHan}\\p{Alnum}]+", " ")
                .trim()
                .split("\\s+")) {
            if (token.length() >= 2 && !stopWords().contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Set<String> stopWords() {
        return Set.of("what", "how", "should", "please", "cite", "the", "and", "for", "with", "when", "this", "that");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record FilterResult(
            List<EvidenceSpan> retained,
            List<ScoredSpan> dropped
    ) {
    }

    public record ScoredSpan(
            EvidenceSpan span,
            double filterScore,
            double lexicalOverlap,
            double sectionAffinity
    ) {
    }
}
