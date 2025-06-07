package com.krickert.yappy.kafka.slot;

import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Manages Kafka consumer slot assignments using Consul for coordination.
 * This allows multiple engine instances to coordinate which partitions they consume
 * without relying solely on Kafka's consumer group rebalancing.
 * 
 * Benefits:
 * - Sticky partition assignments that survive restarts
 * - Ability to manually reassign partitions
 * - Better visibility into which instance handles which partitions
 * - Support for advanced patterns like standby consumers
 */
public interface KafkaSlotManager {
    
    /**
     * Register this engine instance as available to consume slots.
     * 
     * @param engineInstanceId Unique identifier for this engine instance
     * @param maxSlots Maximum number of slots this instance can handle
     * @return Mono indicating completion
     */
    Mono<Void> registerEngine(String engineInstanceId, int maxSlots);
    
    /**
     * Unregister this engine instance and release all its slots.
     * 
     * @param engineInstanceId Engine instance to unregister
     * @return Mono indicating completion
     */
    Mono<Void> unregisterEngine(String engineInstanceId);
    
    /**
     * Get all slots for a given topic across all partitions.
     * 
     * @param topic Kafka topic name
     * @param groupId Consumer group ID
     * @return List of all slots for the topic
     */
    Mono<List<KafkaSlot>> getSlotsForTopic(String topic, String groupId);
    
    /**
     * Acquire available slots for this engine instance.
     * 
     * @param engineInstanceId Engine requesting slots
     * @param topic Topic to consume from
     * @param groupId Consumer group ID
     * @param requestedSlots Number of slots requested (0 = as many as possible)
     * @return Assigned slots
     */
    Mono<SlotAssignment> acquireSlots(String engineInstanceId, String topic, 
                                      String groupId, int requestedSlots);
    
    /**
     * Release slots held by this engine instance.
     * 
     * @param engineInstanceId Engine releasing slots
     * @param slots Slots to release
     * @return Mono indicating completion
     */
    Mono<Void> releaseSlots(String engineInstanceId, List<KafkaSlot> slots);
    
    /**
     * Update heartbeat for slots to maintain ownership.
     * 
     * @param engineInstanceId Engine updating heartbeat
     * @param slots Slots to update
     * @return Mono indicating completion
     */
    Mono<Void> heartbeatSlots(String engineInstanceId, List<KafkaSlot> slots);
    
    /**
     * Get current slot assignments for an engine instance.
     * 
     * @param engineInstanceId Engine instance ID
     * @return Current slot assignments
     */
    Mono<SlotAssignment> getAssignmentsForEngine(String engineInstanceId);
    
    /**
     * Watch for changes in slot assignments.
     * 
     * @param engineInstanceId Engine instance to watch
     * @return Flux of assignment changes
     */
    Flux<SlotAssignment> watchAssignments(String engineInstanceId);
    
    /**
     * Force rebalance of slots across all available engines.
     * 
     * @param topic Topic to rebalance
     * @param groupId Consumer group ID
     * @return Mono indicating completion
     */
    Mono<Void> rebalanceSlots(String topic, String groupId);
    
    /**
     * Get health/status of slot management system.
     * 
     * @return Health status
     */
    Mono<SlotManagerHealth> getHealth();
    
    /**
     * Health status for the slot manager.
     */
    record SlotManagerHealth(
            boolean healthy,
            int totalSlots,
            int assignedSlots,
            int availableSlots,
            int registeredEngines,
            String lastError
    ) {}
}