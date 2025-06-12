package com.krickert.search.orchestrator.kafka.config;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import lombok.Data;

/**
 * Configuration properties for Kafka service operations.
 */
@ConfigurationProperties("kafka.service")
@Data
public class KafkaServiceConfiguration {

    /**
     * Default number of partitions for new topics.
     */
    private int defaultPartitions = 3;

    /**
     * Default replication factor for new topics.
     */
    private short defaultReplicationFactor = 1;

    /**
     * Default timeout for admin operations in milliseconds.
     */
    private long adminTimeoutMs = 30000;

    /**
     * Default timeout for producer operations in milliseconds.
     */
    private long producerTimeoutMs = 10000;

    /**
     * Whether to enable topic auto-creation.
     */
    private boolean autoCreateTopics = true;

    /**
     * Producer configuration override properties.
     */
    @Nullable
    private String producerConfigOverrides;

    /**
     * Consumer configuration override properties.
     */
    @Nullable
    private String consumerConfigOverrides;
}