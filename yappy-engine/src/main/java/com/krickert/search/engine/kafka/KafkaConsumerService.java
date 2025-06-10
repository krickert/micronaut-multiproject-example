package com.krickert.search.engine.kafka;

import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import reactor.core.publisher.Mono;

/**
 * Interface for managing Kafka consumers with slot-based partition assignments.
 */
public interface KafkaConsumerService {
    
    /**
     * Request slots for a specific topic and consumer group.
     * 
     * @param topic Kafka topic
     * @param groupId Consumer group ID
     * @param requestedSlots Number of slots requested (0 = as many as possible)
     * @return Mono indicating completion
     */
    Mono<Void> requestSlots(String topic, String groupId, int requestedSlots);
    
    /**
     * Release slots for a specific topic.
     * 
     * @param topic Topic to release slots for
     * @return Mono indicating completion
     */
    Mono<Void> releaseSlots(String topic);
    
    /**
     * Get current slot assignment for this engine.
     * @return current slot assignment or null
     */
    SlotAssignment getCurrentAssignment();
    
    /**
     * Get active consumer count.
     * @return number of active consumers
     */
    int getActiveConsumerCount();
    
    /**
     * Check if the service is running.
     * @return true if running
     */
    boolean isRunning();
    
    /**
     * Start all consumers.
     */
    void startConsumers();
    
    /**
     * Stop all consumers.
     */
    void stopConsumers();
    
    /**
     * Check if all consumers are healthy.
     * @return true if all consumers are active and running
     */
    boolean isHealthy();
}