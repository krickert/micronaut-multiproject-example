package com.krickert.yappy.modules.opensearchsink.config;

import lombok.Builder;

/**
 * Configuration for the OpenSearch sink.
 * This class defines the configuration options for connecting to an OpenSearch cluster
 * and indexing documents.
 */
@Builder
public record OpenSearchSinkConfig(
    // OpenSearch connection settings
    String hosts,
    Integer port,
    String username,
    String password,
    Boolean useSsl,
    
    // Index settings
    String indexName,
    String indexType,
    String idField,
    
    // Bulk indexing settings
    Integer bulkSize,
    Integer bulkConcurrency,
    
    // Retry settings
    Integer maxRetries,
    Integer retryBackoffMs,
    
    // Logging options
    String logPrefix
) {

}