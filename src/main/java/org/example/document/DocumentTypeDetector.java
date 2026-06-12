package org.example.document;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Locale;

@Service
public class DocumentTypeDetector {
    public DocumentType detect(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        String extension = extension(fileName);
        return switch (extension) {
            case "txt", "log" -> DocumentType.TEXT;
            case "md", "markdown" -> DocumentType.MARKDOWN;
            case "pdf" -> DocumentType.PDF;
            case "doc", "docx" -> DocumentType.WORD;
            case "ppt", "pptx" -> DocumentType.POWERPOINT;
            case "xls", "xlsx", "csv" -> DocumentType.EXCEL;
            case "html", "htm" -> DocumentType.HTML;
            case "png", "jpg", "jpeg", "webp", "bmp", "tif", "tiff" -> DocumentType.IMAGE;
            default -> DocumentType.UNKNOWN;
        };
    }

    private String extension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }
}
