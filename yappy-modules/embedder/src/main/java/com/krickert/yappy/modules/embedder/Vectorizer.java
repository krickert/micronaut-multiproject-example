package com.krickert.yappy.modules.embedder;

import java.util.Collection;
import java.util.List;

/**
 * Interface for vectorizing text into embeddings.
 */
public interface Vectorizer {

    /**
     * Generates a vector embedding for the given text.
     *
     * @param text The text to vectorize
     * @return A float array representing the embedding
     */
    float[] embeddings(String text);

    /**
     * Generates vector embeddings for a batch of text inputs.
     *
     * @param texts The list of input texts to be vectorized
     * @return A list of arrays of floating-point values representing the embeddings
     */
    List<float[]> batchEmbeddings(List<String> texts);

    /**
     * Generates a collection of Float values representing the embedding for the given text.
     * This is a convenience method that converts the float array to a Collection.
     *
     * @param text The text to vectorize
     * @return A collection of Float values representing the embedding
     */
    Collection<Float> getEmbeddings(String text);
    
    /**
     * Gets the model ID used by this vectorizer.
     * 
     * @return the model ID
     */
    String getModelId();
}