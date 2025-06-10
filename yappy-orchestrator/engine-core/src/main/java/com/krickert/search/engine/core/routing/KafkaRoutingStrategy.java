package com.krickert.search.engine.core.routing;

import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.PipeStream;

/**
 * Strategy for determining Kafka-specific routing information.
 * Handles the complexities of Kafka routing including topic naming,
 * partitioning, and error handling.
 */
public interface KafkaRoutingStrategy {
    
    /**
     * Determines the Kafka topic for a given step.
     * 
     * @param stepConfig The pipeline step configuration
     * @param pipeStream The current message
     * @return The topic name
     */
    String determineTopicName(PipelineStepConfig stepConfig, PipeStream pipeStream);
    
    /**
     * Determines the partition key for Kafka routing.
     * This affects message ordering and distribution.
     * 
     * @param pipeStream The message to route
     * @return The partition key
     */
    String determinePartitionKey(PipeStream pipeStream);
    
    /**
     * Gets the dead letter topic for error handling.
     * 
     * @param originalTopic The original topic name
     * @return The DLQ topic name
     */
    String getDeadLetterTopic(String originalTopic);
    
    /**
     * Gets the retry topic for transient failures.
     * 
     * @param originalTopic The original topic name
     * @param retryAttempt The retry attempt number
     * @return The retry topic name
     */
    String getRetryTopic(String originalTopic, int retryAttempt);
    
    /**
     * Determines if a topic should use compaction.
     * 
     * @param topicName The topic name
     * @return True if the topic should be compacted
     */
    boolean isCompactedTopic(String topicName);
}