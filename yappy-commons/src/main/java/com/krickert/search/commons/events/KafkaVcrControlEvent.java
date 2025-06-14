package com.krickert.search.commons.events;

import io.micronaut.core.annotation.Introspected;

import java.time.Instant;
import java.util.UUID;

/**
 * Event for Kafka VCR (Video Cassette Recorder) style control operations.
 * This event is published when pause/resume/rewind/fast-forward operations
 * are requested on Kafka consumers.
 */
@Introspected
public record KafkaVcrControlEvent(
        String eventId,
        VcrOperation operation,
        String pipelineId,
        String stepId,
        String topicName,
        String groupId,
        Instant targetTimestamp,  // For rewind operations
        Integer partition,        // Optional: specific partition
        Instant eventTimestamp
) {
    /**
     * VCR-style operations that can be performed on Kafka consumers.
     */
    public enum VcrOperation {
        PAUSE,
        RESUME,
        REWIND,
        FAST_FORWARD
    }
    
    /**
     * Creates a new VCR control event.
     */
    public KafkaVcrControlEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID().toString();
        }
        if (eventTimestamp == null) {
            eventTimestamp = Instant.now();
        }
        
        // Validate required fields
        if (operation == null) {
            throw new IllegalArgumentException("Operation is required");
        }
        if (pipelineId == null || pipelineId.isBlank()) {
            throw new IllegalArgumentException("Pipeline ID is required");
        }
        if (stepId == null || stepId.isBlank()) {
            throw new IllegalArgumentException("Step ID is required");
        }
        if (topicName == null || topicName.isBlank()) {
            throw new IllegalArgumentException("Topic name is required");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("Group ID is required");
        }
        
        // Validate operation-specific requirements
        if (operation == VcrOperation.REWIND && targetTimestamp == null) {
            throw new IllegalArgumentException("Target timestamp is required for REWIND operation");
        }
    }
    
    /**
     * Factory method for PAUSE operation.
     */
    public static KafkaVcrControlEvent pause(String pipelineId, String stepId, String topicName, String groupId) {
        return new KafkaVcrControlEvent(
                null,
                VcrOperation.PAUSE,
                pipelineId,
                stepId,
                topicName,
                groupId,
                null,
                null,
                null
        );
    }
    
    /**
     * Factory method for RESUME operation.
     */
    public static KafkaVcrControlEvent resume(String pipelineId, String stepId, String topicName, String groupId) {
        return new KafkaVcrControlEvent(
                null,
                VcrOperation.RESUME,
                pipelineId,
                stepId,
                topicName,
                groupId,
                null,
                null,
                null
        );
    }
    
    /**
     * Factory method for REWIND operation.
     */
    public static KafkaVcrControlEvent rewind(String pipelineId, String stepId, String topicName, 
                                             String groupId, Instant targetTimestamp, Integer partition) {
        return new KafkaVcrControlEvent(
                null,
                VcrOperation.REWIND,
                pipelineId,
                stepId,
                topicName,
                groupId,
                targetTimestamp,
                partition,
                null
        );
    }
    
    /**
     * Factory method for FAST_FORWARD operation.
     */
    public static KafkaVcrControlEvent fastForward(String pipelineId, String stepId, String topicName, 
                                                   String groupId, Integer partition) {
        return new KafkaVcrControlEvent(
                null,
                VcrOperation.FAST_FORWARD,
                pipelineId,
                stepId,
                topicName,
                groupId,
                null,
                partition,
                null
        );
    }
}