package org.example.document;

import org.springframework.stereotype.Component;

@Component
public class ExternalParserPlaceholder implements DocumentParser {
    @Override
    public boolean supports(DocumentParseRequest request) {
        return request.documentType() == DocumentType.WORD
                || request.documentType() == DocumentType.POWERPOINT
                || request.documentType() == DocumentType.EXCEL
                || request.documentType() == DocumentType.HTML
                || request.documentType() == DocumentType.IMAGE
                || request.ocrRequested();
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) {
        throw new UnsupportedOperationException(
                "External parsers are not enabled yet. Route this document to Unstructured or MinerU service."
        );
    }

    @Override
    public ParserType parserType() {
        return ParserType.UNSTRUCTURED_EXTERNAL;
    }
}
