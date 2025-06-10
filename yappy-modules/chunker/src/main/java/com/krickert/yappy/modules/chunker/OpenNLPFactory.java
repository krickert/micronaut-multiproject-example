package com.krickert.yappy.modules.chunker; // Or a common utility package within yappy-modules

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton; // Micronaut prefers jakarta.inject
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.sentdetect.NewlineSentenceDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

@Factory
public class OpenNLPFactory {
    private static final Logger log = LoggerFactory.getLogger(OpenNLPFactory.class);

    @Singleton
    public TokenizerME tokenizerME() {
        String modelPath = "/opennlp_models/opennlp-en-ud-ewt-tokens-1.2-2.5.0.bin";
        try {
            InputStream tokenizerModelIn = getClass().getResourceAsStream(modelPath);
            if (tokenizerModelIn == null) {
                log.warn("Tokenizer model not found at: {}. Using fallback tokenizer.", modelPath);
                return new FallbackTokenizer();
            }
            
            // Check if the stream has content
            if (tokenizerModelIn.available() == 0) {
                log.warn("Tokenizer model file is empty at: {}. Using fallback tokenizer.", modelPath);
                tokenizerModelIn.close();
                return new FallbackTokenizer();
            }
            
            // Don't close the stream until after the model is fully loaded
            TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelIn);
            tokenizerModelIn.close();
            return new TokenizerME(tokenizerModel);
        } catch (IOException e) {
            log.error("Failed to load OpenNLP tokenizer model from: {}. Using fallback tokenizer.", modelPath, e);
            return new FallbackTokenizer();
        }
    }

    @Singleton
    public SentenceDetectorME sentenceDetectorME() {
        String modelPath = "/opennlp_models/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin";
        try {
            InputStream sentenceModelIn = getClass().getResourceAsStream(modelPath);
            if (sentenceModelIn == null) {
                log.warn("Sentence model not found at: {}. Using fallback sentence detector.", modelPath);
                return new FallbackSentenceDetector();
            }
            
            // Check if the stream has content
            if (sentenceModelIn.available() == 0) {
                log.warn("Sentence model file is empty at: {}. Using fallback sentence detector.", modelPath);
                sentenceModelIn.close();
                return new FallbackSentenceDetector();
            }
            
            // Don't close the stream until after the model is fully loaded
            SentenceModel sentenceModel = new SentenceModel(sentenceModelIn);
            sentenceModelIn.close();
            return new SentenceDetectorME(sentenceModel);
        } catch (IOException e) {
            log.error("Failed to load OpenNLP sentence model from: {}. Using fallback sentence detector.", modelPath, e);
            return new FallbackSentenceDetector();
        }
    }
}