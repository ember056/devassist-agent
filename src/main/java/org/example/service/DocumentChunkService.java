package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.document.ParsedBlock;
import org.example.document.ParsedDocument;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Splits documents into semantically stable chunks.
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Autowired
    private DocumentChunkConfig chunkConfig;

    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        if (content == null || content.trim().isEmpty()) {
            logger.warn("Document content is empty: {}", filePath);
            return List.of();
        }

        List<Section> sections = splitMarkdownSections(content);
        List<DocumentChunk> chunks = buildChunks(sections, 0, true);
        logger.info("Document chunking completed: {} -> {} chunks", filePath, chunks.size());
        return chunks;
    }

    public List<DocumentChunk> chunkDocument(ParsedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (document == null || document.blocks() == null || document.blocks().isEmpty()) {
            return chunks;
        }

        int globalChunkIndex = 0;
        int blockIndex = 0;
        for (ParsedBlock block : document.blocks()) {
            if (block.text() == null || block.text().isBlank()) {
                blockIndex++;
                continue;
            }

            boolean markdown = "markdown".equalsIgnoreCase(block.type())
                    || document.parserType().name().equalsIgnoreCase("MARKDOWN");
            List<Section> sections = markdown
                    ? splitMarkdownSections(block.text())
                    : List.of(new Section(block.title(), block.title(), block.text(), 0, 0, "plain"));

            List<DocumentChunk> blockChunks = buildChunks(sections, globalChunkIndex, markdown);
            for (DocumentChunk chunk : blockChunks) {
                applyParsedMetadata(chunk, document, block, blockIndex);
            }
            chunks.addAll(blockChunks);
            globalChunkIndex += blockChunks.size();
            blockIndex++;
        }

        logger.info("Document chunking completed: {} -> {} chunks, parser={}",
                document.sourceFile(), chunks.size(), document.parserType());
        return chunks;
    }

    private List<DocumentChunk> buildChunks(List<Section> sections, int startChunkIndex, boolean markdown) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = startChunkIndex;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, chunkIndex, markdown);
            chunks.addAll(sectionChunks);
            chunkIndex += sectionChunks.size();
        }
        return chunks;
    }

    private List<Section> splitMarkdownSections(String content) {
        List<Section> sections = new ArrayList<>();
        String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        String[] headingStack = new String[6];

        String currentTitle = null;
        String currentPath = null;
        int currentLevel = 0;
        int currentStart = 0;
        int charOffset = 0;
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            Matcher matcher = MARKDOWN_HEADING.matcher(line);
            if (matcher.matches()) {
                if (!current.toString().trim().isEmpty()) {
                    sections.add(new Section(
                            currentTitle,
                            currentPath,
                            current.toString().trim(),
                            currentStart,
                            currentLevel,
                            semanticBlockType(currentTitle)
                    ));
                }

                int level = matcher.group(1).length();
                String title = matcher.group(2).trim();
                headingStack[level - 1] = title;
                for (int i = level; i < headingStack.length; i++) {
                    headingStack[i] = null;
                }
                currentTitle = title;
                currentPath = Arrays.stream(headingStack)
                        .filter(value -> value != null && !value.isBlank())
                        .collect(Collectors.joining(" > "));
                currentLevel = level;
                currentStart = charOffset;
                current = new StringBuilder();
                current.append(line).append('\n');
            } else {
                if (current.isEmpty()) {
                    currentStart = charOffset;
                }
                current.append(line).append('\n');
            }
            charOffset += line.length() + 1;
        }

        if (!current.toString().trim().isEmpty()) {
            sections.add(new Section(
                    currentTitle,
                    currentPath,
                    current.toString().trim(),
                    currentStart,
                    currentLevel,
                    semanticBlockType(currentTitle)
            ));
        }

        if (sections.isEmpty()) {
            sections.add(new Section(null, null, content, 0, 0, "plain"));
        }
        return sections;
    }

    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex, boolean markdown) {
        List<DocumentChunk> chunks = new ArrayList<>();
        if (section.content.length() <= chunkConfig.getMaxSize()) {
            chunks.add(createChunk(section, section.content, section.startIndex, startChunkIndex));
            return chunks;
        }

        List<String> paragraphs = splitByParagraphs(section.content);
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            if (currentChunk.length() > 0
                    && currentChunk.length() + paragraph.length() + 2 > chunkConfig.getMaxSize()) {
                String chunkContent = currentChunk.toString().trim();
                chunks.add(createChunk(section, chunkContent, currentStartIndex, chunkIndex++));

                currentChunk = new StringBuilder();
                currentStartIndex = section.startIndex + section.content.indexOf(paragraph);
            }

            if (paragraph.length() > chunkConfig.getMaxSize()) {
                if (currentChunk.length() > 0) {
                    String chunkContent = currentChunk.toString().trim();
                    chunks.add(createChunk(section, chunkContent, currentStartIndex, chunkIndex++));
                    currentChunk = new StringBuilder();
                }
                for (String sentenceChunk : splitLongParagraph(paragraph)) {
                    int start = section.startIndex + section.content.indexOf(sentenceChunk);
                    chunks.add(createChunk(section, sentenceChunk, Math.max(section.startIndex, start), chunkIndex++));
                }
                continue;
            }

            currentChunk.append(paragraph).append("\n\n");
        }

        if (!currentChunk.toString().trim().isEmpty()) {
            chunks.add(createChunk(section, currentChunk.toString().trim(), currentStartIndex, chunkIndex));
        }
        return chunks;
    }

    private DocumentChunk createChunk(Section section, String content, int startIndex, int chunkIndex) {
        DocumentChunk chunk = new DocumentChunk(
                content,
                startIndex,
                startIndex + content.length(),
                chunkIndex
        );
        chunk.setTitle(section.title);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sectionTitle", section.title == null ? "" : section.title);
        metadata.put("headingPath", section.headingPath == null ? "" : section.headingPath);
        metadata.put("headingLevel", section.headingLevel);
        metadata.put("semanticBlockType", section.semanticBlockType);
        chunk.setMetadata(metadata);
        return chunk;
    }

    private void applyParsedMetadata(
            DocumentChunk chunk,
            ParsedDocument document,
            ParsedBlock block,
            int blockIndex
    ) {
        chunk.setPageNumber(block.pageNumber());
        chunk.setBlockType(block.type());
        chunk.setParser(document.parserType().name());
        chunk.setConfidence(block.confidence());
        if ((chunk.getTitle() == null || chunk.getTitle().isBlank())
                && block.title() != null
                && !block.title().isBlank()) {
            chunk.setTitle(block.title());
        }

        Map<String, Object> metadata = new HashMap<>();
        if (chunk.getMetadata() != null) {
            metadata.putAll(chunk.getMetadata());
        }
        metadata.put("documentType", document.documentType().name());
        metadata.put("parser", document.parserType().name());
        metadata.put("blockIndex", blockIndex);
        metadata.put("blockType", block.type());
        if (block.pageNumber() != null) {
            metadata.put("pageNumber", block.pageNumber());
        }
        if (block.confidence() != null) {
            metadata.put("confidence", block.confidence());
        }
        if (block.metadata() != null) {
            metadata.putAll(block.metadata());
        }
        chunk.setMetadata(metadata);
    }

    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = content.split("\\n\\s*\\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    private List<String> splitLongParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？.!?])\\s+|\\n");
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() > chunkConfig.getMaxSize()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(sentence).append(' ');
        }
        if (!current.toString().trim().isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private String semanticBlockType(String title) {
        if (title == null) {
            return "plain";
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.contains("evidence")) {
            return "evidence";
        }
        if (normalized.contains("action") || normalized.contains("response")
                || normalized.contains("排查") || normalized.contains("处理") || normalized.contains("操作")) {
            return "action";
        }
        if (normalized.contains("verification") || normalized.contains("验证")) {
            return "verification";
        }
        if (normalized.contains("safe") || normalized.contains("风险")) {
            return "safety";
        }
        if (normalized.contains("root cause") || normalized.contains("原因")) {
            return "root_cause";
        }
        if (normalized.contains("symptom") || normalized.contains("现象")) {
            return "symptom";
        }
        return "section";
    }

    private record Section(
            String title,
            String headingPath,
            String content,
            int startIndex,
            int headingLevel,
            String semanticBlockType
    ) {
    }
}
