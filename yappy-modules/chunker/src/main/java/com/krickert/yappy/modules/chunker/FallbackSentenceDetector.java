package com.krickert.yappy.modules.chunker;

import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.sentdetect.NewlineSentenceDetector;
import opennlp.tools.util.Span;

/**
 * A fallback sentence detector that uses newline detection when OpenNLP models are not available.
 * This is mainly for testing purposes.
 */
public class FallbackSentenceDetector extends SentenceDetectorME {
    
    private final NewlineSentenceDetector newlineDetector = new NewlineSentenceDetector();
    
    public FallbackSentenceDetector() {
        super((SentenceModel) null);
    }
    
    @Override
    public String[] sentDetect(String s) {
        return newlineDetector.sentDetect(s);
    }
    
    @Override
    public Span[] sentPosDetect(String s) {
        return newlineDetector.sentPosDetect(s);
    }
}