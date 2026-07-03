package org.example.service;

public record EvidenceSpan(
        Type type,
        String text,
        VectorSearchService.SearchResult source,
        String sourceFile,
        String headingPath,
        String sectionTitle,
        String chunkIndex,
        double supportScore
) {
    public enum Type {
        EVIDENCE,
        ACTION,
        SAFETY,
        VERIFICATION,
        CONTEXT
    }

    public String citationLabel() {
        StringBuilder label = new StringBuilder();
        int index = source == null || source.getSourceIndex() == null ? 0 : source.getSourceIndex();
        label.append("参考资料 ").append(index).append("：").append(sourceFile == null || sourceFile.isBlank() ? "unknown" : sourceFile);
        if (headingPath != null && !headingPath.isBlank()) {
            label.append(" > ").append(headingPath);
        } else if (sectionTitle != null && !sectionTitle.isBlank()) {
            label.append(" > ").append(sectionTitle);
        }
        if (chunkIndex != null && !chunkIndex.isBlank()) {
            label.append(" > chunk ").append(chunkIndex);
        }
        return label.toString();
    }

    public String normalizedText() {
        return text == null ? "" : text.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
