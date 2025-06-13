package com.krickert.search.orchestrator.kafka.listener;

import java.time.Instant;

/**
 * Represents the status of a Kafka consumer for API responses.
 * This is a record class that provides a view of consumer status information
 * suitable for returning in API responses.
 */
public record ConsumerStatus(
        String id,
        String pipelineName,
        String stepName,
        String topic,
        String groupId,
        boolean paused,
        Instant lastUpdated) {
    
    /**
     * Creates a new ConsumerStatus with the given parameters.
     * 
     * @param id The ID of the consumer
     * @param pipelineName The name of the pipeline the consumer is associated with
     * @param stepName The name of the step the consumer is associated with
     * @param topic The topic the consumer is subscribed to
     * @param groupId The consumer group ID
     * @param paused Whether the consumer is paused
     * @param lastUpdated When the status was last updated
     */
    public ConsumerStatus {
        // Validation
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Consumer ID cannot be null or blank");
        }
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Topic cannot be null or blank");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Group ID cannot be null or blank");
        }
        if (lastUpdated == null) {
            throw new IllegalArgumentException("Last updated timestamp cannot be null");
        }
    }
}