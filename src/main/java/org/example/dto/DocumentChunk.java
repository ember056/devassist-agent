package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Setter
@Getter
public class DocumentChunk {
    private String content;
    private int startIndex;
    private int endIndex;
    private int chunkIndex;
    private String title;

    private Integer pageNumber;
    private String blockType;
    private String parser;
    private Double confidence;
    private Map<String, Object> metadata = new HashMap<>();

    public DocumentChunk() {
    }

    public DocumentChunk(String content, int startIndex, int endIndex, int chunkIndex) {
        this.content = content;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkIndex = chunkIndex;
    }

    @Override
    public String toString() {
        return "DocumentChunk{" +
                "chunkIndex=" + chunkIndex +
                ", title='" + title + '\'' +
                ", contentLength=" + (content != null ? content.length() : 0) +
                ", startIndex=" + startIndex +
                ", endIndex=" + endIndex +
                ", pageNumber=" + pageNumber +
                ", blockType='" + blockType + '\'' +
                '}';
    }
}
