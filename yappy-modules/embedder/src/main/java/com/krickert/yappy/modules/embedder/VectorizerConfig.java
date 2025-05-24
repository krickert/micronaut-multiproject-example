package com.krickert.yappy.modules.embedder;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Nullable;

/**
 * Configuration properties for a vectorizer.
 * This class is used to configure instances of DJLVectorizer.
 */
@EachProperty("vectorizer")
public class VectorizerConfig {

    /**
     * The embedding model to use.
     */
    private EmbeddingModel model = EmbeddingModel.ALL_MINILM_L6_V2; // Default model

    /**
     * The size of the predictor pool.
     */
    private int poolSize = 4; // Default pool size

    /**
     * The prefix to add to query texts for search queries.
     */
    @Nullable
    private String queryPrefix;

    /**
     * The prefix to add to document texts for indexing.
     */
    @Nullable
    private String documentPrefix;

    /**
     * Gets the embedding model to use.
     *
     * @return the embedding model
     */
    public EmbeddingModel getModel() {
        return model;
    }

    /**
     * Sets the embedding model to use.
     *
     * @param model the embedding model
     */
    public void setModel(EmbeddingModel model) {
        this.model = model;
    }

    /**
     * Gets the size of the predictor pool.
     *
     * @return the pool size
     */
    public int getPoolSize() {
        return poolSize;
    }

    /**
     * Sets the size of the predictor pool.
     *
     * @param poolSize the pool size
     */
    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    /**
     * Gets the prefix to add to query texts for search queries.
     *
     * @return the query prefix, or null if not set
     */
    @Nullable
    public String getQueryPrefix() {
        return queryPrefix;
    }

    /**
     * Sets the prefix to add to query texts for search queries.
     *
     * @param queryPrefix the query prefix
     */
    public void setQueryPrefix(@Nullable String queryPrefix) {
        this.queryPrefix = queryPrefix;
    }

    /**
     * Gets the prefix to add to document texts for indexing.
     *
     * @return the document prefix, or null if not set
     */
    @Nullable
    public String getDocumentPrefix() {
        return documentPrefix;
    }

    /**
     * Sets the prefix to add to document texts for indexing.
     *
     * @param documentPrefix the document prefix
     */
    public void setDocumentPrefix(@Nullable String documentPrefix) {
        this.documentPrefix = documentPrefix;
    }
}