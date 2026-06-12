package org.example.document;

import java.nio.file.Path;

public record DocumentParseRequest(
        Path path,
        DocumentType documentType,
        boolean ocrRequested
) {
}
