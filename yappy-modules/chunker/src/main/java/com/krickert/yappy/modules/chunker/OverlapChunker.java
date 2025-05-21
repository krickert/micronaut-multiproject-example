package com.krickert.yappy.modules.chunker;

import com.google.protobuf.Message;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.mapper.MappingException;
import com.krickert.search.model.mapper.ValueHandler;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.util.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class OverlapChunker {

    private static final Logger log = LoggerFactory.getLogger(OverlapChunker.class);
    private static final long MAX_TEXT_BYTES = 100 * 1024 * 1024; // 100MB limit
    private final ValueHandler valueHandler;
    private final TokenizerME tokenizer;

    private static final Pattern URL_PATTERN = Pattern.compile(
            "\\b(?:https?|ftp|file)://[-a-zA-Z0-9+&@#/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#/%=~_|]",
            Pattern.CASE_INSENSITIVE);
    private static final String URL_PLACEHOLDER_PREFIX = "__URL_PLACEHOLDER_";
    private static final String URL_PLACEHOLDER_SUFFIX = "__";


    @Inject
    public OverlapChunker(ValueHandler valueHandler, TokenizerME tokenizer) {
        this.valueHandler = valueHandler;
        this.tokenizer = tokenizer;
    }


    // squish method remains as is if still needed elsewhere, or be removed if not.
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


    private String transformURLsToPlaceholders(String text, Map<String, String> placeholderToUrlMap, List<Span> urlSpans) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int placeholderIndex = 0;
        while (matcher.find()) {
            String placeholder = URL_PLACEHOLDER_PREFIX + placeholderIndex + URL_PLACEHOLDER_SUFFIX;
            String url = matcher.group(0);
            placeholderToUrlMap.put(placeholder, url);
            urlSpans.add(new Span(matcher.start(), matcher.end(), "URL")); // Store original URL span
            matcher.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
            placeholderIndex++;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String restorePlaceholdersInChunk(String chunkText, Map<String, String> placeholderToUrlMap) {
        if (chunkText == null || chunkText.isEmpty() || placeholderToUrlMap.isEmpty()) {
            return chunkText;
        }
        String restoredText = chunkText;
        for (Map.Entry<String, String> entry : placeholderToUrlMap.entrySet()) {
            restoredText = restoredText.replaceAll(Pattern.quote(entry.getKey()), Matcher.quoteReplacement(entry.getValue()));
        }
        return restoredText;
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


    // Change return type to ChunkingResult
    public ChunkingResult createChunks(PipeDoc document, ChunkerOptions options, String streamId, String pipeStepName) throws MappingException {
        if (document == null) {
            log.warn("Input document is null. Cannot create chunks. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
            return new ChunkingResult(Collections.emptyList(), Collections.emptyMap()); // Return empty result
        }
        String documentId = document.getId();
        String textFieldPath = options.sourceField();

        Optional<String> textOptional = extractTextFromPipeDoc(document, textFieldPath);
        if (textOptional.isEmpty() || textOptional.get().trim().isEmpty()) {
            log.warn("No text found or text is empty at path '{}'. No chunks will be created. streamId: {}, pipeStepName: {}", textFieldPath, streamId, pipeStepName);
            return new ChunkingResult(Collections.emptyList(), Collections.emptyMap()); // Return empty result
        }
        String originalText = textOptional.get();

        // Handle MAX_TEXT_BYTES before URL processing to avoid issues with placeholder lengths
        byte[] originalTextBytes = originalText.getBytes(StandardCharsets.UTF_8);
        if (originalTextBytes.length > MAX_TEXT_BYTES) {
            log.warn("Original text from field '{}' exceeds MAX_TEXT_BYTES ({} bytes). Truncating. streamId: {}, pipeStepName: {}",
                    textFieldPath, MAX_TEXT_BYTES, streamId, pipeStepName);
            originalText = new String(originalTextBytes, 0, (int) MAX_TEXT_BYTES, StandardCharsets.UTF_8);
        }

        Map<String, String> placeholderToUrlMap = new HashMap<>();
        List<Span> originalUrlSpans = new ArrayList<>(); // To store original URL positions
        String textToProcess = originalText;

        if (options.preserveUrls() != null && options.preserveUrls()) {
            textToProcess = transformURLsToPlaceholders(originalText, placeholderToUrlMap, originalUrlSpans);
            log.debug("Text after URL placeholder replacement: {}", textToProcess);
        }

        Span[] tokenSpans = tokenizer.tokenizePos(textToProcess); // Get tokens with their character spans
        String[] tokens = Span.spansToStrings(tokenSpans, textToProcess);

        if (tokens.length == 0) {
            log.info("No tokens found after tokenization for document part from field '{}'. streamId: {}, pipeStepName: {}", textFieldPath, streamId, pipeStepName);
            return new ChunkingResult(Collections.emptyList(), placeholderToUrlMap); // Return empty chunks but include map
        }

        log.info("Creating chunks with target character size: {}, character overlap: {}, for document ID: {}, streamId: {}, pipeStepName: {}",
                options.chunkSize(), options.chunkOverlap(), documentId, streamId, pipeStepName);

        List<Chunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        int currentTokenStartIndex = 0;

        while (currentTokenStartIndex < tokens.length) {
            int currentChunkCharCount = 0;
            int currentTokenEndIndex = currentTokenStartIndex;
            StringBuilder currentChunkTextBuilder = new StringBuilder();

            // Determine the actual start character offset of the first token in this chunk
            // This offset is relative to textToProcess (which might have placeholders)
            int chunkStartCharOffsetInProcessedText = tokenSpans[currentTokenStartIndex].getStart();

            boolean firstTokenInChunk = true; // Flag to avoid leading space

            while (currentTokenEndIndex < tokens.length) {
                String tokenText = tokens[currentTokenEndIndex];
                int tokenCharLength = tokenText.length();
                boolean isPlaceholder = tokenText.startsWith(URL_PLACEHOLDER_PREFIX) && tokenText.endsWith(URL_PLACEHOLDER_SUFFIX);

                int potentialLength = currentChunkTextBuilder.length() + (currentChunkTextBuilder.length() > 0 ? 1 : 0) + tokenCharLength;
                if (isPlaceholder && (options.preserveUrls() != null && options.preserveUrls())) {
                    String originalUrl = placeholderToUrlMap.get(tokenText);
                    if (originalUrl != null) {
                        potentialLength = currentChunkTextBuilder.length() + (currentChunkTextBuilder.length() > 0 ? 1 : 0) + originalUrl.length();
                    }
                }

                if (!firstTokenInChunk && potentialLength > options.chunkSize()) { // Check length before adding if not the first token
                    break;
                }

                if (!firstTokenInChunk) {
                    // Smart spacing: Don't add a space if the current token is punctuation
                    // that typically doesn't have a preceding space, or if the previous token
                    // was an opening bracket, etc. This can be made more sophisticated.
                    // A common set of "no-space-before" punctuation: . , ! ? : ; ' " ) ] }
                    // A common set of "no-space-after" punctuation: ( [ {
                    // For simplicity now, just check for common sentence-ending punctuation.
                    if (!(tokenText.length() == 1 && ".?!,:;)]}".contains(tokenText))) {
                        // Also, don't add a space if the previous token ended with something like an opening quote or bracket
                        if (currentChunkTextBuilder.length() > 0) {
                            char lastCharOfPrevious = currentChunkTextBuilder.charAt(currentChunkTextBuilder.length() - 1);
                            if (!"([{".contains(String.valueOf(lastCharOfPrevious))) {
                                currentChunkTextBuilder.append(" ");
                            }
                        } else {
                            currentChunkTextBuilder.append(" "); // Should not happen if firstTokenInChunk is true
                        }
                    }
                }
                currentChunkTextBuilder.append(tokenText);
                firstTokenInChunk = false; // No longer the first token after one is appended
                // currentChunkCharCount = currentChunkTextBuilder.length(); // Recalculate if needed, but potentialLength check is key
                currentTokenEndIndex++;
            }

            if (currentChunkTextBuilder.length() == 0 && currentTokenEndIndex < tokens.length) {
                // Handle cases where a single token might be larger than chunk_size
                // or if we are at the very end with a small remaining token.
                // For now, we'll just take the single token if the builder is empty.
                currentChunkTextBuilder.append(tokens[currentTokenStartIndex]);
                currentTokenEndIndex = currentTokenStartIndex + 1;
            }


            String chunkTextWithPlaceholders = currentChunkTextBuilder.toString().trim();
            if (chunkTextWithPlaceholders.isEmpty()) {
                if (currentTokenEndIndex >= tokens.length) break; // No more tokens to process
                currentTokenStartIndex = currentTokenEndIndex; // Skip empty formation and advance
                continue;
            }

            // Determine the actual end character offset of the last token in this chunk
            // This offset is relative to textToProcess
            int chunkEndCharOffsetInProcessedText = tokenSpans[currentTokenEndIndex - 1].getEnd();


            String finalChunkText = chunkTextWithPlaceholders;
            if (options.preserveUrls() != null && options.preserveUrls()) {
                finalChunkText = restorePlaceholdersInChunk(chunkTextWithPlaceholders, placeholderToUrlMap);
            }

            // --- Calculate Original Offsets ---
            // This is the most complex part if URLs were replaced.
            // We need to map chunkStartCharOffsetInProcessedText and chunkEndCharOffsetInProcessedText
            // back to offsets in the *originalText*.
            // If preserveUrls is false, tokenSpans directly relate to originalText.

            int originalStartOffset = chunkStartCharOffsetInProcessedText;
            int originalEndOffset = chunkEndCharOffsetInProcessedText -1; // Span.getEnd() is exclusive

            if (options.preserveUrls() != null && options.preserveUrls() && !placeholderToUrlMap.isEmpty()) {
                // Placeholder for accurate offset recalculation logic
                // This would involve iterating through originalUrlSpans and adjusting offsets
                // based on whether the chunk's span in processedText overlaps with placeholder spans.
                // For now, we'll use the processed text offsets, which will be inaccurate if URLs were replaced.
                // A production system would need a robust solution here.
                log.warn("URL preservation is active, original character offsets for chunks might be approximate " +
                         "due to placeholder substitutions. StreamID: {}, DocID: {}", streamId, documentId);
            }


            String chunkId = String.format(options.chunkIdTemplate(), streamId, documentId, chunkIndex++);
            chunks.add(new Chunk(chunkId, finalChunkText, originalStartOffset, originalEndOffset));

            // Determine next starting token for overlap
            if (currentTokenEndIndex >= tokens.length) {
                break; // Reached the end of tokens
            }

            // Calculate overlap in terms of characters and find a suitable token to start next chunk
            int desiredOverlapChars = options.chunkOverlap();
            int currentChunkLengthChars = chunkEndCharOffsetInProcessedText - chunkStartCharOffsetInProcessedText;
            int stepBackChars = Math.max(0, currentChunkLengthChars - desiredOverlapChars);

            int nextTokenStartCharTarget = chunkStartCharOffsetInProcessedText + stepBackChars;

            int nextTokenCandidate = currentTokenStartIndex; // Start searching from the beginning of the current chunk
            for (int i = currentTokenStartIndex; i < currentTokenEndIndex; i++) {
                if (tokenSpans[i].getStart() >= nextTokenStartCharTarget) {
                    nextTokenCandidate = i;
                    break;
                }
                // If no token starts exactly at/after target, the last token before it is the best bet
                // or simply the one that makes the overlap closest to desired.
                // This logic can be refined.
                nextTokenCandidate = i;
            }
            // Ensure progress: if the overlap is too large or chunks too small,
            // we must advance at least one token.
            currentTokenStartIndex = Math.max(currentTokenStartIndex + 1, nextTokenCandidate);


        }

        log.info("Created {} token-based chunks for document part from field '{}'. streamId: {}, pipeStepName: {}",
                chunks.size(), textFieldPath, streamId, pipeStepName);
        // Return the chunks and the map
        return new ChunkingResult(chunks, placeholderToUrlMap);
    }
}