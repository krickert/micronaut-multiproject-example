package com.krickert.yappy.modules.chunker; // Or a common utility package within yappy-modules

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton; // Micronaut prefers jakarta.inject
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
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
        try (InputStream tokenizerModelIn = getClass().getResourceAsStream(modelPath)) {
            if (tokenizerModelIn == null) {
                throw new IOException("Tokenizer model not found at: " + modelPath);
            }
            TokenizerModel tokenizerModel = new TokenizerModel(tokenizerModelIn);
            return new TokenizerME(tokenizerModel);
        } catch (IOException e) {
            log.error("Failed to load OpenNLP tokenizer model from: {}", modelPath, e);
            throw new IllegalStateException("Could not initialize OpenNLP tokenizer model", e);
        }
    }

    @Singleton
    public SentenceDetectorME sentenceDetectorME() {
        String modelPath = "/opennlp_models/opennlp-en-ud-ewt-sentence-1.2-2.5.0.bin";
        try (InputStream sentenceModelIn = getClass().getResourceAsStream(modelPath)) {
            if (sentenceModelIn == null) {
                throw new IOException("Sentence model not found at: " + modelPath);
            }
            SentenceModel sentenceModel = new SentenceModel(sentenceModelIn);
            return new SentenceDetectorME(sentenceModel);
        } catch (IOException e) {
            log.error("Failed to load OpenNLP sentence model from: {}", modelPath, e);
            throw new IllegalStateException("Could not initialize OpenNLP sentence model", e);
        }
    }
}