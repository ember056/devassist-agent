package org.example.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvidenceSpanExtractorService {
    private static final int MAX_SPANS_PER_SOURCE = 12;

    public List<EvidenceSpan> extract(String question, List<VectorSearchService.SearchResult> sources) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        Map<String, EvidenceSpan> deduped = new LinkedHashMap<>();
        for (VectorSearchService.SearchResult source : sources) {
            for (EvidenceSpan span : extractFromSource(question, source)) {
                String key = span.type() + "|" + span.sourceFile() + "|" + span.headingPath() + "|" + span.normalizedText();
                deduped.putIfAbsent(key, span);
            }
        }
        return List.copyOf(deduped.values());
    }

    private List<EvidenceSpan> extractFromSource(String question, VectorSearchService.SearchResult source) {
        String content = source.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<EvidenceSpan> spans = new ArrayList<>();
        addMarkedBlock(spans, source, EvidenceSpan.Type.EVIDENCE, "Evidence:");
        addMarkedBlock(spans, source, EvidenceSpan.Type.ACTION, "Actions:");
        addMarkedBlock(spans, source, EvidenceSpan.Type.SAFETY, "Safe Operations:");
        addMarkedBlock(spans, source, EvidenceSpan.Type.VERIFICATION, "Verification:");

        if (spans.isEmpty()) {
            addFallbackSpans(spans, source);
        }

        return spans.stream()
                .sorted((left, right) -> Double.compare(right.supportScore(), left.supportScore()))
                .limit(MAX_SPANS_PER_SOURCE)
                .toList();
    }

    private void addMarkedBlock(
            List<EvidenceSpan> spans,
            VectorSearchService.SearchResult source,
            EvidenceSpan.Type type,
            String marker
    ) {
        boolean collecting = false;
        for (String rawLine : source.getContent().lines().toList()) {
            String line = rawLine.trim();
            if (line.equalsIgnoreCase(marker)) {
                collecting = true;
                continue;
            }
            if (!collecting) {
                continue;
            }
            if (isSectionBoundary(line)) {
                break;
            }
            if (isUsableLine(line)) {
                spans.add(toSpan(source, type, cleanLine(line), 1.0));
            }
        }
    }

    private void addFallbackSpans(List<EvidenceSpan> spans, VectorSearchService.SearchResult source) {
        EvidenceSpan.Type type = fallbackType(source);
        for (String rawLine : source.getContent().lines().toList()) {
            String line = rawLine.trim();
            if (isUsableLine(line) && !isMarker(line)) {
                spans.add(toSpan(source, type, cleanLine(line), 0.72));
            }
            if (spans.size() >= MAX_SPANS_PER_SOURCE) {
                return;
            }
        }
        if (spans.isEmpty()) {
            String title = metadataValue(source, "sectionTitle");
            if (title.isBlank()) {
                title = metadataValue(source, "title");
            }
            if (!title.isBlank()) {
                spans.add(toSpan(source, EvidenceSpan.Type.CONTEXT, title, 0.55));
            }
        }
    }

    private EvidenceSpan.Type fallbackType(VectorSearchService.SearchResult source) {
        String blockType = metadataValue(source, "semanticBlockType").toLowerCase(Locale.ROOT);
        String headingPath = metadataValue(source, "headingPath").toLowerCase(Locale.ROOT);
        String title = metadataValue(source, "title").toLowerCase(Locale.ROOT);
        if (blockType.equals("action") || headingPath.contains("first response") || headingPath.contains("actions")
                || title.contains("first response") || title.contains("actions")) {
            return EvidenceSpan.Type.ACTION;
        }
        if (blockType.equals("safety") || headingPath.contains("safe operations") || title.contains("safe")) {
            return EvidenceSpan.Type.SAFETY;
        }
        if (blockType.equals("verification") || headingPath.contains("verification") || title.contains("verification")) {
            return EvidenceSpan.Type.VERIFICATION;
        }
        if (blockType.equals("symptom") || blockType.equals("evidence")) {
            return EvidenceSpan.Type.EVIDENCE;
        }
        return EvidenceSpan.Type.CONTEXT;
    }

    private EvidenceSpan toSpan(
            VectorSearchService.SearchResult source,
            EvidenceSpan.Type type,
            String text,
            double baseScore
    ) {
        double retrievalScore = source.getHybridScore() == null ? 0.0 : Math.max(0.0, Math.min(1.0, source.getHybridScore()));
        double rerankScore = source.getRerankScore() == null ? 0.0 : Math.max(0.0, Math.min(1.0, source.getRerankScore()));
        double supportScore = Math.min(1.0, baseScore * 0.75 + retrievalScore * 0.15 + rerankScore * 0.10);
        return new EvidenceSpan(
                type,
                text,
                source,
                sourceName(source),
                metadataValue(source, "headingPath"),
                firstNonBlank(metadataValue(source, "sectionTitle"), metadataValue(source, "title")),
                metadataValue(source, "chunkIndex"),
                supportScore
        );
    }

    private boolean isSectionBoundary(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.startsWith("#")
                || isMarker(line)
                || line.equalsIgnoreCase("Symptoms:")
                || line.equalsIgnoreCase("Root Cause Candidates:");
    }

    private boolean isMarker(String line) {
        return line.equalsIgnoreCase("Evidence:")
                || line.equalsIgnoreCase("Actions:")
                || line.equalsIgnoreCase("Safe Operations:")
                || line.equalsIgnoreCase("Verification:");
    }

    private boolean isUsableLine(String line) {
        if (line == null || line.isBlank() || line.length() < 12) {
            return false;
        }
        return line.startsWith("-")
                || line.matches("^\\d+\\.\\s+.*")
                || line.matches("^(Check|Confirm|Identify|Compare|Search|Preserve|Reduce|Tune|Add|Split|Replace|Do not|Avoid|Prefer|Temporarily|Enable|Inspect|Roll back|Validate|Query|Monitor|Restart|Scale|Throttle|Pause|Resume|Move|Replay|Use).*");
    }

    private String cleanLine(String line) {
        return line.replaceFirst("^\\d+\\.\\s*", "")
                .replaceFirst("^-\\s*", "")
                .trim();
    }

    private String sourceName(VectorSearchService.SearchResult source) {
        String metadata = source.getMetadata();
        if (metadata == null) {
            return "unknown";
        }
        int key = metadata.indexOf("\"_file_name\"");
        if (key < 0) {
            return "unknown";
        }
        int colon = metadata.indexOf(':', key);
        int firstQuote = metadata.indexOf('"', colon + 1);
        int secondQuote = firstQuote < 0 ? -1 : metadata.indexOf('"', firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) {
            return "unknown";
        }
        return metadata.substring(firstQuote + 1, secondQuote);
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
        int valueStart = colon + 1;
        while (valueStart < metadata.length() && Character.isWhitespace(metadata.charAt(valueStart))) {
            valueStart++;
        }
        if (valueStart >= metadata.length()) {
            return "";
        }
        if (metadata.charAt(valueStart) == '"') {
            int valueEnd = metadata.indexOf('"', valueStart + 1);
            if (valueEnd < 0) {
                return "";
            }
            return metadata.substring(valueStart + 1, valueEnd);
        }
        int comma = metadata.indexOf(',', valueStart);
        int endBrace = metadata.indexOf('}', valueStart);
        int valueEnd = comma < 0 ? endBrace : Math.min(comma, endBrace < 0 ? comma : endBrace);
        if (valueEnd < 0) {
            valueEnd = metadata.length();
        }
        return metadata.substring(valueStart, valueEnd).trim();
    }

    private String firstNonBlank(String left, String right) {
        return left == null || left.isBlank() ? right == null ? "" : right : left;
    }
}
