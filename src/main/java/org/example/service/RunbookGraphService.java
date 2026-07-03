package org.example.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds a lightweight per-query runbook graph over retrieved evidence spans.
 *
 * <p>The first version intentionally stays in memory. It treats markdown
 * sections as graph neighborhoods: evidence/actions under the same root-cause
 * heading are siblings, while document-level safety and verification sections
 * are adjacent guardrail nodes for every root cause in the same runbook.
 */
@Service
public class RunbookGraphService {
    private static final int MAX_SIBLINGS_PER_ROOT_CAUSE = 8;
    private static final int MAX_GUARDRAILS_PER_TYPE = 3;

    public GraphExpansion expand(
            String question,
            List<EvidenceSpan> seedSpans,
            List<EvidenceSpan> candidateSpans
    ) {
        if (seedSpans == null || seedSpans.isEmpty()) {
            return new GraphExpansion(List.of(), 0, 0, List.of());
        }

        List<EvidenceSpan> candidates = candidateSpans == null || candidateSpans.isEmpty()
                ? seedSpans
                : candidateSpans;
        candidates = merge(candidates, localRunbookSpans(seedSpans, candidates));
        Map<String, List<EvidenceSpan>> byRootCause = candidates.stream()
                .filter(span -> !rootCauseKey(span).isBlank())
                .collect(Collectors.groupingBy(
                        span -> sourceKey(span) + "|" + rootCauseKey(span),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        Map<String, List<EvidenceSpan>> guardrailsBySource = candidates.stream()
                .filter(this::isDocumentGuardrail)
                .collect(Collectors.groupingBy(
                        this::sourceKey,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        Set<String> activatedRootCauses = activatedRootCauses(question, seedSpans, byRootCause);
        Map<String, EvidenceSpan> expanded = new LinkedHashMap<>();
        seedSpans.forEach(span -> expanded.putIfAbsent(dedupeKey(span), span));

        int siblingCount = 0;
        int guardrailCount = 0;
        for (String rootCause : activatedRootCauses) {
            List<EvidenceSpan> siblings = byRootCause.getOrDefault(rootCause, List.of()).stream()
                    .sorted(Comparator.comparing(EvidenceSpan::supportScore).reversed())
                    .limit(MAX_SIBLINGS_PER_ROOT_CAUSE)
                    .toList();
            for (EvidenceSpan sibling : siblings) {
                if (expanded.putIfAbsent(dedupeKey(sibling), sibling) == null) {
                    siblingCount++;
                }
            }

            String source = rootCause.substring(0, rootCause.indexOf('|'));
            List<EvidenceSpan> guardrails = guardrailsBySource.getOrDefault(source, List.of());
            guardrailCount += addGuardrails(expanded, guardrails, EvidenceSpan.Type.SAFETY);
            guardrailCount += addGuardrails(expanded, guardrails, EvidenceSpan.Type.VERIFICATION);
        }

        List<EvidenceSpan> result = List.copyOf(expanded.values());
        List<String> activatedLabels = activatedRootCauses.stream()
                .map(value -> value.substring(value.indexOf('|') + 1))
                .distinct()
                .toList();
        return new GraphExpansion(result, siblingCount, guardrailCount, activatedLabels);
    }

    private Set<String> activatedRootCauses(
            String question,
            List<EvidenceSpan> seedSpans,
            Map<String, List<EvidenceSpan>> byRootCause
    ) {
        Set<String> activated = new LinkedHashSet<>();
        for (EvidenceSpan span : seedSpans) {
            String rootCause = rootCauseKey(span);
            if (!rootCause.isBlank()) {
                activated.add(sourceKey(span) + "|" + rootCause);
            }
        }

        Set<String> questionTerms = tokenize(question);
        if (!questionTerms.isEmpty()) {
            byRootCause.entrySet().stream()
                    .sorted((left, right) -> Double.compare(
                            rootCauseQuestionAffinity(questionTerms, right.getValue()),
                            rootCauseQuestionAffinity(questionTerms, left.getValue())
                    ))
                    .filter(entry -> rootCauseQuestionAffinity(questionTerms, entry.getValue()) > 0.0)
                    .limit(2)
                    .map(Map.Entry::getKey)
                    .forEach(activated::add);
        }
        if (isBroadTroubleshootingQuestion(question)) {
            Set<String> seedSources = seedSpans.stream()
                    .map(this::sourceKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            byRootCause.keySet().stream()
                    .filter(key -> seedSources.contains(key.substring(0, key.indexOf('|'))))
                    .limit(4)
                    .forEach(activated::add);
        }
        return activated;
    }

    private List<EvidenceSpan> localRunbookSpans(
            List<EvidenceSpan> seedSpans,
            List<EvidenceSpan> candidateSpans
    ) {
        Map<String, VectorSearchService.SearchResult> templateByFile = new LinkedHashMap<>();
        for (EvidenceSpan span : merge(seedSpans, candidateSpans)) {
            if (!sourceKey(span).equals("unknown") && span.source() != null) {
                templateByFile.putIfAbsent(sourceKey(span), span.source());
            }
        }
        if (templateByFile.isEmpty()) {
            return List.of();
        }

        List<EvidenceSpan> spans = new ArrayList<>();
        for (Map.Entry<String, VectorSearchService.SearchResult> entry : templateByFile.entrySet()) {
            spans.addAll(parseRunbookFile(entry.getKey(), entry.getValue()));
        }
        return spans;
    }

    private List<EvidenceSpan> parseRunbookFile(
            String sourceFile,
            VectorSearchService.SearchResult templateSource
    ) {
        Path path = resolveRunbookPath(sourceFile);
        if (path == null) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(path);
            return parseRunbookLines(sourceFile, templateSource, lines);
        } catch (IOException ignored) {
            return List.of();
        }
    }

    private Path resolveRunbookPath(String sourceFile) {
        for (Path candidate : List.of(
                Path.of("aiops-docs", sourceFile),
                Path.of("uploads", sourceFile)
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<EvidenceSpan> parseRunbookLines(
            String sourceFile,
            VectorSearchService.SearchResult templateSource,
            List<String> lines
    ) {
        List<EvidenceSpan> spans = new ArrayList<>();
        String[] headingStack = new String[6];
        String sectionTitle = "";
        String headingPath = "";
        EvidenceSpan.Type currentType = null;
        int chunkIndex = 9000;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.matches("^#{1,6}\\s+.+")) {
                int level = line.indexOf(' ');
                String title = line.substring(level + 1).trim();
                int headingLevel = level;
                headingStack[headingLevel - 1] = title;
                for (int i = headingLevel; i < headingStack.length; i++) {
                    headingStack[i] = null;
                }
                sectionTitle = title;
                headingPath = headingPath(headingStack);
                currentType = documentLevelType(sectionTitle);
                continue;
            }
            if (line.equalsIgnoreCase("Evidence:")) {
                currentType = EvidenceSpan.Type.EVIDENCE;
                continue;
            }
            if (line.equalsIgnoreCase("Actions:")) {
                currentType = EvidenceSpan.Type.ACTION;
                continue;
            }
            if (line.equalsIgnoreCase("Safe Operations:")) {
                currentType = EvidenceSpan.Type.SAFETY;
                continue;
            }
            if (line.equalsIgnoreCase("Verification:")) {
                currentType = EvidenceSpan.Type.VERIFICATION;
                continue;
            }
            if (line.isBlank() || currentType == null || !isRunbookBullet(line)) {
                continue;
            }

            String text = cleanBullet(line);
            VectorSearchService.SearchResult source = syntheticSource(
                    sourceFile,
                    templateSource,
                    text,
                    headingPath,
                    sectionTitle,
                    String.valueOf(chunkIndex++),
                    currentType
            );
            spans.add(new EvidenceSpan(
                    currentType,
                    text,
                    source,
                    sourceFile,
                    headingPath,
                    sectionTitle,
                    metadataValue(source, "chunkIndex"),
                    0.72
            ));
        }
        return spans;
    }

    private VectorSearchService.SearchResult syntheticSource(
            String sourceFile,
            VectorSearchService.SearchResult templateSource,
            String text,
            String headingPath,
            String sectionTitle,
            String chunkIndex,
            EvidenceSpan.Type type
    ) {
        VectorSearchService.SearchResult source = new VectorSearchService.SearchResult();
        source.setId("runbook-graph:" + sourceFile + ":" + chunkIndex);
        source.setContent(text);
        source.setScore(templateSource == null ? 0.0f : templateSource.getScore());
        source.setHybridScore(scoreOrDefault(templateSource == null ? null : templateSource.getHybridScore(), 0.65));
        source.setRerankScore(scoreOrDefault(templateSource == null ? null : templateSource.getRerankScore(), 0.65));
        source.setBm25Score(scoreOrDefault(templateSource == null ? null : templateSource.getBm25Score(), 0.65));
        source.setRetrievalMode("runbook_graph");
        source.setSourceIndex(templateSource == null ? 0 : templateSource.getSourceIndex());
        source.setMetadata(metadataJson(sourceFile, headingPath, sectionTitle, chunkIndex, semanticBlockType(type)));
        return source;
    }

    private EvidenceSpan.Type documentLevelType(String sectionTitle) {
        String normalized = safe(sectionTitle).toLowerCase(Locale.ROOT);
        if (normalized.contains("safe")) {
            return EvidenceSpan.Type.SAFETY;
        }
        if (normalized.contains("verification")) {
            return EvidenceSpan.Type.VERIFICATION;
        }
        return null;
    }

    private boolean isRunbookBullet(String line) {
        return line.startsWith("- ") || line.matches("^\\d+\\.\\s+.+");
    }

    private String cleanBullet(String line) {
        return line.replaceFirst("^\\d+\\.\\s*", "")
                .replaceFirst("^-\\s*", "")
                .trim();
    }

    private String headingPath(String[] headingStack) {
        return java.util.Arrays.stream(headingStack)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" > "));
    }

    private String semanticBlockType(EvidenceSpan.Type type) {
        return switch (type) {
            case EVIDENCE -> "evidence";
            case ACTION -> "action";
            case SAFETY -> "safety";
            case VERIFICATION -> "verification";
            case CONTEXT -> "context";
        };
    }

    private String metadataJson(
            String sourceFile,
            String headingPath,
            String sectionTitle,
            String chunkIndex,
            String semanticBlockType
    ) {
        return "{"
                + "\"_file_name\":\"" + jsonEscape(sourceFile) + "\","
                + "\"headingPath\":\"" + jsonEscape(headingPath) + "\","
                + "\"sectionTitle\":\"" + jsonEscape(sectionTitle) + "\","
                + "\"title\":\"" + jsonEscape(sectionTitle) + "\","
                + "\"chunkIndex\":\"" + jsonEscape(chunkIndex) + "\","
                + "\"semanticBlockType\":\"" + jsonEscape(semanticBlockType) + "\""
                + "}";
    }

    private Double scoreOrDefault(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private String metadataValue(VectorSearchService.SearchResult source, String keyName) {
        String metadata = source.getMetadata();
        if (metadata == null || keyName == null || keyName.isBlank()) {
            return "";
        }
        String key = "\"" + keyName + "\"";
        int keyIndex = metadata.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }
        int colon = metadata.indexOf(':', keyIndex + key.length());
        if (colon < 0) {
            return "";
        }
        int valueStart = metadata.indexOf('"', colon + 1);
        int valueEnd = valueStart < 0 ? -1 : metadata.indexOf('"', valueStart + 1);
        if (valueStart < 0 || valueEnd < 0) {
            return "";
        }
        return metadata.substring(valueStart + 1, valueEnd);
    }

    private String jsonEscape(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private List<EvidenceSpan> merge(List<EvidenceSpan> left, List<EvidenceSpan> right) {
        Map<String, EvidenceSpan> merged = new LinkedHashMap<>();
        if (left != null) {
            left.forEach(span -> merged.putIfAbsent(dedupeKey(span), span));
        }
        if (right != null) {
            right.forEach(span -> merged.putIfAbsent(dedupeKey(span), span));
        }
        return List.copyOf(merged.values());
    }

    private int addGuardrails(
            Map<String, EvidenceSpan> expanded,
            List<EvidenceSpan> guardrails,
            EvidenceSpan.Type type
    ) {
        int added = 0;
        for (EvidenceSpan span : guardrails.stream()
                .filter(item -> item.type() == type)
                .sorted(Comparator.comparing(EvidenceSpan::supportScore).reversed())
                .limit(MAX_GUARDRAILS_PER_TYPE)
                .toList()) {
            if (expanded.putIfAbsent(dedupeKey(span), span) == null) {
                added++;
            }
        }
        return added;
    }

    private double rootCauseQuestionAffinity(Set<String> questionTerms, List<EvidenceSpan> spans) {
        Set<String> rootCauseTerms = new LinkedHashSet<>();
        for (EvidenceSpan span : spans) {
            rootCauseTerms.addAll(tokenize(String.join(" ",
                    safe(span.headingPath()),
                    safe(span.sectionTitle()),
                    safe(span.text())
            )));
        }
        if (rootCauseTerms.isEmpty()) {
            return 0.0;
        }
        long hits = questionTerms.stream().filter(rootCauseTerms::contains).count();
        return hits / (double) Math.min(6, questionTerms.size());
    }

    private boolean isDocumentGuardrail(EvidenceSpan span) {
        if (span.type() != EvidenceSpan.Type.SAFETY && span.type() != EvidenceSpan.Type.VERIFICATION) {
            return false;
        }
        return rootCauseKey(span).isBlank();
    }

    private String rootCauseKey(EvidenceSpan span) {
        String headingPath = safe(span.headingPath());
        String[] parts = headingPath.split("\\s*>\\s*");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equalsIgnoreCase("Root Cause Candidates") && i + 1 < parts.length) {
                return normalizeLabel(parts[i + 1]);
            }
        }
        String section = safe(span.sectionTitle());
        if (section.isBlank() || isDocumentLevelSection(section)) {
            return "";
        }
        return normalizeLabel(section);
    }

    private boolean isDocumentLevelSection(String section) {
        String normalized = section.toLowerCase(Locale.ROOT);
        return normalized.contains("alert")
                || normalized.contains("runbook")
                || normalized.contains("symptom")
                || normalized.contains("first response")
                || normalized.contains("safe")
                || normalized.contains("verification")
                || normalized.contains("related evidence keyword")
                || normalized.contains("root cause candidates");
    }

    private boolean isBroadTroubleshootingQuestion(String question) {
        String normalized = safe(question).toLowerCase(Locale.ROOT);
        if (!(normalized.contains("troubleshoot")
                || normalized.contains("investigate")
                || normalized.contains("analyze")
                || normalized.contains("排查")
                || normalized.contains("分析")
                || normalized.contains("故障")
                || normalized.contains("timeout")
                || normalized.contains("latency")
                || normalized.contains("slow")
                || normalized.contains("变慢"))) {
            return false;
        }
        return !(normalized.contains("hot key")
                || normalized.contains("big key")
                || normalized.contains("cache avalanche")
                || normalized.contains("connection leak")
                || normalized.contains("lock wait")
                || normalized.contains("poison message")
                || normalized.contains("schema mismatch")
                || normalized.contains("downstream dependency")
                || normalized.contains("probe")
                || normalized.contains("bad deployment")
                || normalized.contains("oomkilled")
                || normalized.contains("crashloopbackoff"));
    }

    private String sourceKey(EvidenceSpan span) {
        return safe(span.sourceFile()).isBlank() ? "unknown" : span.sourceFile();
    }

    private String dedupeKey(EvidenceSpan span) {
        return span.type() + "|" + sourceKey(span) + "|" + safe(span.headingPath()) + "|" + span.normalizedText();
    }

    private String normalizeLabel(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
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

    public record GraphExpansion(
            List<EvidenceSpan> spans,
            int siblingSpanCount,
            int guardrailSpanCount,
            List<String> activatedRootCauses
    ) {
    }
}
