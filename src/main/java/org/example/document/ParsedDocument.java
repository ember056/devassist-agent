package org.example.document;

import java.util.List;
import java.util.Map;

public record ParsedDocument(
        String documentId,
        String sourceFile,
        DocumentType documentType,
        ParserType parserType,
        List<ParsedBlock> blocks,
        Map<String, Object> metadata
) {
    public String plainText() {
        StringBuilder builder = new StringBuilder();
        for (ParsedBlock block : blocks) {
            if (block.text() == null || block.text().isBlank()) {
                continue;
            }
            if (block.title() != null && !block.title().isBlank()) {
                builder.append("# ").append(block.title()).append("\n\n");
            }
            builder.append(block.text().trim()).append("\n\n");
        }
        return builder.toString().trim();
    }
}
