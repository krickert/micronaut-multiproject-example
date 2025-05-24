package com.krickert.yappy.modules.opensearchsink.config;

import org.opensearch.client.RestClientBuilder;

/**
 * Interface for providing credentials to OpenSearch client.
 * Different implementations can handle different authentication methods.
 */
public interface OpenSearchCredentialsProvider {
    
    /**
     * Configure the RestClientBuilder with the appropriate authentication.
     * 
     * @param builder the RestClientBuilder to configure
     * @return the configured RestClientBuilder
     */
    RestClientBuilder configureCredentials(RestClientBuilder builder);
    
    /**
     * Check if SSL should be enabled for this credentials provider.
     * 
     * @return true if SSL should be enabled, false otherwise
     */
    boolean isUseSsl();
}