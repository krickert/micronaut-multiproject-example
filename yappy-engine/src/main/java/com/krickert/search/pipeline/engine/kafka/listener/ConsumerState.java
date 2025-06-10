package com.krickert.search.pipeline.engine.kafka.listener;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * Represents the state of a Kafka consumer.
 * This is a record class that stores information about a consumer's state,
 * including its ID, topic, group ID, whether it's paused, when it was last updated,
 * and the current offsets for each partition.
 */
public record ConsumerState(
        String consumerId,
        String topic,
        String groupId,
        boolean paused,
        Instant lastUpdated,
        Map<Integer, Long> partitionOffsets) {
    
    /**
     * Creates a new ConsumerState with the given parameters.
     * 
     * @param consumerId The ID of the consumer
     * @param topic The topic the consumer is subscribed to
     * @param groupId The consumer group ID
     * @param paused Whether the consumer is paused
     * @param lastUpdated When the state was last updated
     * @param partitionOffsets The current offsets for each partition
     */
    public ConsumerState {
        // Defensive copy of mutable map
        partitionOffsets = partitionOffsets != null ? 
                Collections.unmodifiableMap(partitionOffsets) : 
                Collections.emptyMap();
    }
}