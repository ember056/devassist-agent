package org.example.document;

public interface DocumentParser {
    boolean supports(DocumentParseRequest request);

    ParsedDocument parse(DocumentParseRequest request) throws Exception;

    ParserType parserType();
}
