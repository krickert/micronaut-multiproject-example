package com.krickert.search.pipeline.engine.kafka.admin.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for Kafka topics created for pipeline steps.
 * These properties are loaded from application.yml or Consul config.
 */
@ConfigurationProperties("kafka.topics.pipeline")
@Context
@Getter
@Setter
public class PipelineKafkaTopicConfig {
    
    /**
     * Default number of partitions for pipeline Kafka topics.
     * Default: 4
     */
    private int partitions = 4;
    
    /**
     * Default replication factor for pipeline Kafka topics.
     * Default: 2
     */
    private short replicationFactor = 2;
    
    /**
     * Default compression type for pipeline Kafka topics.
     * Default: lz4
     */
    private String compressionType = "lz4";
    
    /**
     * Default max message size for pipeline Kafka topics in bytes.
     * Default: 8MB (8 * 1024 * 1024)
     */
    private int maxMessageBytes = 8 * 1024 * 1024;
    
    /**
     * Default retention period for pipeline Kafka topics in milliseconds.
     * Default: 3 months (90 days)
     */
    private long retentionMs = 90L * 24 * 60 * 60 * 1000; // 90 days in milliseconds
    
    /**
     * Whether to enable compaction for pipeline Kafka topics.
     * Default: true
     */
    private boolean compactEnabled = true;
}