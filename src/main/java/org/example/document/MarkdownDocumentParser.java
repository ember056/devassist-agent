package org.example.document;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@Component
public class MarkdownDocumentParser implements DocumentParser {
    @Override
    public boolean supports(DocumentParseRequest request) {
        return request.documentType() == DocumentType.MARKDOWN;
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) throws Exception {
        String text = Files.readString(request.path());
        ParsedBlock block = new ParsedBlock(text, "markdown", null, null, 1.0, Map.of());
        return new ParsedDocument(
                request.path().toString(),
                request.path().toString(),
                request.documentType(),
                parserType(),
                List.of(block),
                Map.of("parser", parserType().name())
        );
    }

    @Override
    public ParserType parserType() {
        return ParserType.MARKDOWN;
    }
}
