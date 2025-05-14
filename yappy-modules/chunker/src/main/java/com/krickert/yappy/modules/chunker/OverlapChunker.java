package com.krickert.yappy.modules.chunker;

import com.google.protobuf.Message;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.mapper.ValueHandler;
import com.krickert.search.model.mapper.MappingException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Singleton
public class OverlapChunker {

    private static final Logger log = LoggerFactory.getLogger(OverlapChunker.class);
    private final ValueHandler valueHandler;
    private static final long MAX_TEXT_BYTES = 100 * 1024 * 1024; // 100MB limit

    public OverlapChunker(ValueHandler valueHandler) {
        this.valueHandler = valueHandler;
    }

    // squish and transformURLsToWords methods remain the same as your last provided version
    public List<String> squish(List<String> list) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        StringBuilder currentString = new StringBuilder();
        for (String s : list) {
            if (s != null && !s.isEmpty()) {
                if (currentString.length() > 0) {
                    currentString.append(" ");
                }
                currentString.append(s.trim());
            }
        }
        if (currentString.length() > 0) {
            result.add(currentString.toString());
        }
        return result;
    }

    private String transformURLsToWords(String text) {
        if (text == null) return "";
        return text.replaceAll("https?://\\S+", "urlplaceholder");
    }

    private Optional<String> extractTextFromPipeDoc(Message document, String fieldPath) throws MappingException {
        if (document == null || fieldPath == null || fieldPath.isEmpty()) {
            return Optional.empty();
        }
        try {
            Object valueObject = valueHandler.getValue(document, fieldPath, "OverlapChunker.extractText");

            if (valueObject != null) {
                if (valueObject instanceof String) {
                    return Optional.of((String) valueObject);
                } else if (valueObject instanceof com.google.protobuf.ByteString) {
                    return Optional.of(((com.google.protobuf.ByteString) valueObject).toString(StandardCharsets.UTF_8));
                } else {
                    log.warn("Field '{}' is not of type String or ByteString. Actual type: {}. Will attempt String.valueOf().",
                            fieldPath, valueObject.getClass().getName());
                    return Optional.of(String.valueOf(valueObject));
                }
            }
            log.warn("Value not present or null for field path: '{}' in document.", fieldPath);
            return Optional.empty();
        } catch (MappingException e) {
            log.error("MappingException while trying to extract field '{}': {}", fieldPath, e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error extracting field '{}': {}", fieldPath, e.getMessage(), e);
            throw new MappingException("Error extracting field " + fieldPath + ": " + e.getMessage(), e, fieldPath);
        }
    }

    public List<Chunk> createChunks(PipeDoc document, ChunkerOptions options, String streamId, String pipeStepName) throws MappingException {
        if (document == null) {
            log.warn("Input document is null. Cannot create chunks. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
            return Collections.emptyList();
        }

        // Get document ID for chunk ID generation
        String documentId = document.getId();

        // Use sourceField() from ChunkerOptions
        String textFieldPath = options.sourceField();

        Optional<String> textOptional = extractTextFromPipeDoc(document, textFieldPath);
        if (textOptional.isEmpty() || textOptional.get().trim().isEmpty()) {
            log.warn("No text found or text is empty at path '{}'. No chunks will be created. streamId: {}, pipeStepName: {}", textFieldPath, streamId, pipeStepName);
            return Collections.emptyList();
        }
        String text = textOptional.get();

        String processedText = transformURLsToWords(text);
        byte[] textBytes = processedText.getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > MAX_TEXT_BYTES) {
            log.warn("Text from field '{}' exceeds MAX_TEXT_BYTES ({} bytes). Truncating. streamId: {}, pipeStepName: {}", textFieldPath, MAX_TEXT_BYTES, streamId, pipeStepName);
            processedText = new String(textBytes, 0, (int) MAX_TEXT_BYTES, StandardCharsets.UTF_8);
        }

        List<Chunk> chunks = new ArrayList<>();
        String[] lines = processedText.split("\\r?\\n");
        int chunkIndex = 0;
        int charOffset = 0;

        List<String> currentChunkLines = new ArrayList<>();
        int currentChunkSize = 0;
        int currentChunkCharStartIndex = 0;

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isEmpty()) {
                charOffset += line.length() + 1; // +1 for the newline character
                continue;
            }

            int lineLength = trimmedLine.length();

            // Use chunkSize() from ChunkerOptions
            if (currentChunkSize + (currentChunkLines.isEmpty() ? 0 : 1) + lineLength > options.chunkSize() && !currentChunkLines.isEmpty()) {
                String chunkText = String.join(" ", currentChunkLines);
                // Use chunkIdTemplate() from ChunkerOptions. Example: streamId, documentId, chunkIndex
                String chunkId = String.format(options.chunkIdTemplate(), streamId, documentId, chunkIndex++);
                // Use new Chunk record definition
                chunks.add(new Chunk(chunkId, chunkText, currentChunkCharStartIndex, charOffset - 1));

                currentChunkLines.clear();
                currentChunkLines.add(trimmedLine);
                currentChunkSize = lineLength;
                currentChunkCharStartIndex = charOffset;
            } else {
                currentChunkLines.add(trimmedLine);
                currentChunkSize += (currentChunkLines.size() == 1 ? 0 : 1) + lineLength;
            }
            charOffset += line.length() + 1;
        }

        if (!currentChunkLines.isEmpty()) {
            String chunkText = String.join(" ", currentChunkLines);
            String chunkId = String.format(options.chunkIdTemplate(), streamId, documentId, chunkIndex++);
            // Use new Chunk record definition
            chunks.add(new Chunk(chunkId, chunkText, currentChunkCharStartIndex, charOffset - 1));
        }
        log.info("Created {} chunks for document part from field '{}'. streamId: {}, pipeStepName: {}", chunks.size(), textFieldPath, streamId, pipeStepName);
        return chunks;
    }
}
