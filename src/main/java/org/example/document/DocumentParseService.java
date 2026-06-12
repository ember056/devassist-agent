package org.example.document;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Service
public class DocumentParseService {
    private final DocumentTypeDetector typeDetector;
    private final List<DocumentParser> parsers;

    public DocumentParseService(DocumentTypeDetector typeDetector, List<DocumentParser> parsers) {
        this.typeDetector = typeDetector;
        this.parsers = parsers.stream()
                .sorted(Comparator.comparing(parser -> parser.parserType().ordinal()))
                .toList();
    }

    public ParsedDocument parse(Path path) throws Exception {
        return parse(path, false);
    }

    public ParsedDocument parse(Path path, boolean ocrRequested) throws Exception {
        DocumentParseRequest request = new DocumentParseRequest(
                path.normalize(),
                typeDetector.detect(path),
                ocrRequested
        );

        for (DocumentParser parser : parsers) {
            if (parser.supports(request)) {
                return parser.parse(request);
            }
        }

        throw new UnsupportedOperationException("Unsupported document type: " + request.documentType());
    }
}
