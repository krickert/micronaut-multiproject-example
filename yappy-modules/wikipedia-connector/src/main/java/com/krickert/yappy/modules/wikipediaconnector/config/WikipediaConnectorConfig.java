package com.krickert.yappy.modules.wikipediaconnector.config;

import lombok.Builder;

/**
 * Configuration for the Wikipedia connector.
 * This class defines the configuration options for connecting to a Wikipedia database
 * and processing its contents.
 */
@Builder
public record WikipediaConnectorConfig(
    // Database connection settings
    String host,
    int port,
    String database,
    String user,
    String password,
    
    // Wikipedia dump settings
    String dumpPath,
    String language,
    
    // Article filtering
    String categoryFilter,
    String titleFilter,
    int maxArticles,
    
    // Processing options
    boolean includeCategories,
    boolean includeDiscussions,
    boolean includeRedirects,
    
    // Kafka settings
    String kafkaTopic,
    
    // Logging options
    String logPrefix
) {
    /**
     * Returns a builder with default values.
     * @return a builder with default values
     */
    public static WikipediaConnectorConfigBuilder defaults() {
        return WikipediaConnectorConfig.builder()
                .host("localhost")
                .port(3306)
                .language("en")
                .maxArticles(100)
                .includeCategories(false)
                .includeDiscussions(false)
                .includeRedirects(false)
                .logPrefix("");
    }
}