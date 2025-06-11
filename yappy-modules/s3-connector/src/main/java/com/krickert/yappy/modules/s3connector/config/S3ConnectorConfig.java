package com.krickert.yappy.modules.s3connector.config;

import lombok.Builder;

/**
 * Configuration for the S3 connector.
 * This class defines the configuration options for connecting to an S3 bucket
 * and processing its contents.
 */
@Builder
public record S3ConnectorConfig(
    // S3 connection settings
    String bucketName,
    String region,
    String endpoint,
    String accessKey,
    String secretKey,
    
    // S3 object filtering
    String prefix,
    String suffix,
    
    // Processing options
    boolean recursive,
    int maxKeys,
    
    // Kafka settings
    String kafkaTopic,
    
    // Logging options
    String logPrefix
) {
    /**
     * Returns a builder with default values.
     * @return a builder with default values
     */
    public static S3ConnectorConfigBuilder defaults() {
        return S3ConnectorConfig.builder()
                .region("us-east-1")
                .recursive(true)
                .maxKeys(100)
                .logPrefix("");
    }
}