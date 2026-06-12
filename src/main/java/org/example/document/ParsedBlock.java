package org.example.document;

import java.util.Map;

public record ParsedBlock(
        String text,
        String type,
        Integer pageNumber,
        String title,
        Double confidence,
        Map<String, Object> metadata
) {
}
