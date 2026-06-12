package org.example.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PdfBoxDocumentParser implements DocumentParser {
    @Value("${document.parse.pdf.min-page-text-length:80}")
    private int minPageTextLength;

    @Override
    public boolean supports(DocumentParseRequest request) {
        return request.documentType() == DocumentType.PDF && !request.ocrRequested();
    }

    @Override
    public ParsedDocument parse(DocumentParseRequest request) throws Exception {
        try (PDDocument document = PDDocument.load(request.path().toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ParsedBlock> blocks = new ArrayList<>();
            int pages = document.getNumberOfPages();

            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = normalize(stripper.getText(document));
                if (!text.isBlank()) {
                    blocks.add(new ParsedBlock(
                            text,
                            "pdf_page",
                            page,
                            "Page " + page,
                            1.0,
                            Map.of("page", page)
                    ));
                }
            }

            int totalTextLength = blocks.stream()
                    .mapToInt(block -> block.text() == null ? 0 : block.text().length())
                    .sum();
            int averagePageLength = pages == 0 ? 0 : totalTextLength / pages;
            if (blocks.isEmpty() || averagePageLength < minPageTextLength) {
                throw new IllegalStateException(
                        "PDF text layer is too sparse; this file may be scanned and should use OCR/MinerU."
                );
            }

            return new ParsedDocument(
                    request.path().toString(),
                    request.path().toString(),
                    request.documentType(),
                    parserType(),
                    blocks,
                    Map.of(
                            "parser", parserType().name(),
                            "pageCount", pages,
                            "averagePageTextLength", averagePageLength
                    )
            );
        }
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    @Override
    public ParserType parserType() {
        return ParserType.PDFBOX;
    }
}
