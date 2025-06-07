package com.krickert.yappy.kafka.slot.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Represents the slot assignments for an engine instance.
 */
public record SlotAssignment(
        String engineInstanceId,
        List<KafkaSlot> assignedSlots,
        Instant assignedAt,
        Instant lastUpdated
) {
    
    /**
     * Get slots grouped by topic.
     */
    public Map<String, List<KafkaSlot>> getSlotsByTopic() {
        return assignedSlots.stream()
                .collect(Collectors.groupingBy(KafkaSlot::getTopic));
    }
    
    /**
     * Get slots for a specific topic.
     */
    public List<KafkaSlot> getSlotsForTopic(String topic) {
        return assignedSlots.stream()
                .filter(slot -> slot.getTopic().equals(topic))
                .collect(Collectors.toList());
    }
    
    /**
     * Get partition numbers for a specific topic.
     */
    public List<Integer> getPartitionsForTopic(String topic) {
        return getSlotsForTopic(topic).stream()
                .map(KafkaSlot::getPartition)
                .sorted()
                .collect(Collectors.toList());
    }
    
    /**
     * Check if this assignment is empty.
     */
    public boolean isEmpty() {
        return assignedSlots == null || assignedSlots.isEmpty();
    }
    
    /**
     * Get total number of assigned slots.
     */
    public int getSlotCount() {
        return assignedSlots != null ? assignedSlots.size() : 0;
    }
    
    /**
     * Create an empty assignment.
     */
    public static SlotAssignment empty(String engineInstanceId) {
        return new SlotAssignment(
                engineInstanceId,
                List.of(),
                Instant.now(),
                Instant.now()
        );
    }
}