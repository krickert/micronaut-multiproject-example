package com.krickert.yappy.modules.chunker;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import jakarta.inject.Singleton;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.regex.Pattern;

@Singleton
public class ChunkMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(ChunkMetadataExtractor.class);
    private static final DecimalFormat DECIMAL_FORMAT = new DecimalFormat("#.####");
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("^\\s*([*\\-+•]|[0-9]+[.)])\\s+.*");


    private final SentenceDetectorME sentenceDetector;
    private final TokenizerME tokenizer;

    public ChunkMetadataExtractor() {
        // Initialize OpenNLP models
        // Models should be in src/main/resources or a configurable path
        try (InputStream sentenceModelIn = getClass().getResourceAsStream("/opennlp_models/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin"); // Adjust path as needed
             InputStream tokenizerModelIn = getClass().getResourceAsStream("/opennlp_models/opennlp-en-ud-ewt-tokens-1.2-2.5.0.bin")) { // Adjust path as needed

            if (sentenceModelIn == null) {
                throw new IOException("Sentence model not found! Ensure 'en-sent.bin' is in resources/opennlp_models.");
            }
            SentenceModel sentenceModel = new SentenceModel(sentenceModelIn);
            this.sentenceDetector = new SentenceDetectorME(sentenceModel);

            if (tokenizerModelIn == null) {
                throw new IOException("Tokenizer model not found! Ensure 'en-token.bin' is in resources/opennlp_models.");
            }
            TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelIn);
            this.tokenizer = new TokenizerME(tokenizerModel);

        } catch (IOException e) {
            log.error("Failed to load OpenNLP models. Metadata extraction will be limited.", e);
            throw new IllegalStateException("Could not initialize OpenNLP models", e);
        }
    }

    public Map<String, Value> extractAllMetadata(String chunkText, int chunkNumber, int totalChunksInDocument, boolean containsUrlPlaceholder) {
        Map<String, Value> metadataMap = new HashMap<>();

        if (StringUtils.isBlank(chunkText)) {
            // Populate with defaults or skip for blank chunks
            metadataMap.put("word_count", Value.newBuilder().setNumberValue(0).build());
            metadataMap.put("character_count", Value.newBuilder().setNumberValue(0).build());
            metadataMap.put("sentence_count", Value.newBuilder().setNumberValue(0).build());
            // ... add other defaults as appropriate
            return metadataMap;
        }

        // Basic counts
        int characterCount = chunkText.length();
        metadataMap.put("character_count", Value.newBuilder().setNumberValue(characterCount).build());

        String[] sentences = sentenceDetector.sentDetect(chunkText);
        int sentenceCount = sentences.length;
        metadataMap.put("sentence_count", Value.newBuilder().setNumberValue(sentenceCount).build());

        String[] tokens = tokenizer.tokenize(chunkText);
        int wordCount = tokens.length; // Using token count as word count
        metadataMap.put("word_count", Value.newBuilder().setNumberValue(wordCount).build());

        // Derived averages
        double avgWordLength = wordCount > 0 ? (double) Arrays.stream(tokens).mapToInt(String::length).sum() / wordCount : 0;
        metadataMap.put("average_word_length", Value.newBuilder().setNumberValue(Double.parseDouble(DECIMAL_FORMAT.format(avgWordLength))).build());

        double avgSentenceLength = sentenceCount > 0 ? (double) wordCount / sentenceCount : 0;
        metadataMap.put("average_sentence_length", Value.newBuilder().setNumberValue(Double.parseDouble(DECIMAL_FORMAT.format(avgSentenceLength))).build());

        // Vocabulary Density (Type-Token Ratio)
        if (wordCount > 0) {
            Set<String> uniqueTokens = new HashSet<>(Arrays.asList(tokens));
            double ttr = (double) uniqueTokens.size() / wordCount;
            metadataMap.put("vocabulary_density", Value.newBuilder().setNumberValue(Double.parseDouble(DECIMAL_FORMAT.format(ttr))).build());
        } else {
            metadataMap.put("vocabulary_density", Value.newBuilder().setNumberValue(0).build());
        }

        // Character type percentages
        long whitespaceChars = chunkText.chars().filter(Character::isWhitespace).count();
        long alphanumericChars = chunkText.chars().filter(Character::isLetterOrDigit).count();
        long digitChars = chunkText.chars().filter(Character::isDigit).count();
        long uppercaseChars = chunkText.chars().filter(Character::isUpperCase).count();

        metadataMap.put("whitespace_percentage", Value.newBuilder().setNumberValue(characterCount > 0 ? Double.parseDouble(DECIMAL_FORMAT.format((double) whitespaceChars / characterCount)) : 0).build());
        metadataMap.put("alphanumeric_percentage", Value.newBuilder().setNumberValue(characterCount > 0 ? Double.parseDouble(DECIMAL_FORMAT.format((double) alphanumericChars / characterCount)) : 0).build());
        metadataMap.put("digit_percentage", Value.newBuilder().setNumberValue(characterCount > 0 ? Double.parseDouble(DECIMAL_FORMAT.format((double) digitChars / characterCount)) : 0).build());
        metadataMap.put("uppercase_percentage", Value.newBuilder().setNumberValue(characterCount > 0 ? Double.parseDouble(DECIMAL_FORMAT.format((double) uppercaseChars / characterCount)) : 0).build());

        // Punctuation Counts
        Struct.Builder punctuationStruct = Struct.newBuilder();
        Map<Character, Integer> puncCounts = new HashMap<>();
        for (char c : chunkText.toCharArray()) {
            if (StringUtils.isAsciiPrintable(String.valueOf(c)) && !Character.isLetterOrDigit(c) && !Character.isWhitespace(c)) {
                puncCounts.put(c, puncCounts.getOrDefault(c, 0) + 1);
            }
        }
        for (Map.Entry<Character, Integer> entry : puncCounts.entrySet()) {
            punctuationStruct.putFields(String.valueOf(entry.getKey()), Value.newBuilder().setNumberValue(entry.getValue()).build());
        }
        metadataMap.put("punctuation_counts", Value.newBuilder().setStructValue(punctuationStruct).build());

        // Structural/Positional
        metadataMap.put("is_first_chunk", Value.newBuilder().setBoolValue(chunkNumber == 0).build());
        metadataMap.put("is_last_chunk", Value.newBuilder().setBoolValue(chunkNumber == totalChunksInDocument - 1).build());
        if (totalChunksInDocument > 0) {
            double relativePosition = (totalChunksInDocument == 1) ? 0.5 : (double) chunkNumber / (totalChunksInDocument - 1); // Avoid div by zero for single chunk; scale 0 to 1
            if (totalChunksInDocument == 1) relativePosition = 0.0; // Or 0.5 to indicate it's the only one. Let's stick to 0.0 for first.
            else relativePosition = (double) chunkNumber / (totalChunksInDocument - 1);

            metadataMap.put("relative_position", Value.newBuilder().setNumberValue(Double.parseDouble(DECIMAL_FORMAT.format(relativePosition))).build());
        } else {
            metadataMap.put("relative_position", Value.newBuilder().setNumberValue(0).build());
        }


        // Content Indicators
        metadataMap.put("contains_urlplaceholder", Value.newBuilder().setBoolValue(containsUrlPlaceholder).build());
        metadataMap.put("list_item_indicator", Value.newBuilder().setBoolValue(LIST_ITEM_PATTERN.matcher(chunkText).matches()).build());
        metadataMap.put("potential_heading_score", Value.newBuilder().setNumberValue(calculatePotentialHeadingScore(chunkText, tokens, sentenceCount)).build());


        return metadataMap;
    }

    private double calculatePotentialHeadingScore(String chunkText, String[] tokens, int sentenceCount) {
        double score = 0.0;
        if (tokens.length == 0) return 0.0;

        // Shorter chunks are more likely to be headings
        if (tokens.length < 10) score += 0.2;
        if (tokens.length < 5) score += 0.2;

        // Fewer sentences (ideally 1)
        if (sentenceCount == 1) score += 0.3;

        // Ends with no significant punctuation (or specific heading-like punctuation if any)
        char lastChar = chunkText.charAt(chunkText.length() - 1);
        if (Character.isLetterOrDigit(lastChar)) {
            score += 0.2;
        }

        // Higher uppercase ratio (e.g., title case or all caps)
        long uppercaseWords = Arrays.stream(tokens)
                .filter(token -> token.length() > 0 && Character.isUpperCase(token.charAt(0)))
                .count();
        if ((double) uppercaseWords / tokens.length > 0.7) { // Most words start with uppercase
            score += 0.2;
        }
        if (StringUtils.isAllUpperCase(chunkText.replaceAll("\\s+", ""))) { // All caps
            score += 0.2; // Extra boost for all caps
        }


        return Math.min(1.0, Double.parseDouble(DECIMAL_FORMAT.format(score))); // Normalize to 0-1
    }

    // Helper to convert String to Protobuf Value - not strictly needed if done inline
    private Value toValue(String s) {
        return Value.newBuilder().setStringValue(s).build();
    }

    private Value toValue(double d) {
        return Value.newBuilder().setNumberValue(d).build();
    }

    private Value toValue(long l) {
        return Value.newBuilder().setNumberValue(l).build();
    }

    private Value toValue(boolean b) {
        return Value.newBuilder().setBoolValue(b).build();
    }

}