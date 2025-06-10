package com.krickert.yappy.modules.chunker;

import opennlp.tools.tokenize.TokenizerME;
import opennlp.tools.tokenize.TokenizerModel;
import opennlp.tools.tokenize.WhitespaceTokenizer;
import opennlp.tools.util.Span;

/**
 * A fallback tokenizer that uses whitespace tokenization when OpenNLP models are not available.
 * This is mainly for testing purposes.
 */
public class FallbackTokenizer extends TokenizerME {
    
    private final WhitespaceTokenizer whitespaceTokenizer = WhitespaceTokenizer.INSTANCE;
    
    public FallbackTokenizer() {
        super((TokenizerModel) null);
    }
    
    @Override
    public String[] tokenize(String s) {
        return whitespaceTokenizer.tokenize(s);
    }
    
    @Override
    public Span[] tokenizePos(String s) {
        return whitespaceTokenizer.tokenizePos(s);
    }
}