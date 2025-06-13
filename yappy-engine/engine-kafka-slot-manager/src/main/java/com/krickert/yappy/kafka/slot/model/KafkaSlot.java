package com.krickert.yappy.kafka.slot.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a single Kafka partition slot that can be assigned to an engine instance.
 */
public class KafkaSlot {
    private String topic;
    private int partition;
    private String groupId;
    private String assignedEngine;
    private Instant assignedAt;
    private Instant lastHeartbeat;
    private SlotStatus status;
    
    public enum SlotStatus {
        AVAILABLE,      // No engine assigned
        ASSIGNED,       // Assigned to an engine
        HEARTBEAT_EXPIRED, // Assignment expired due to missing heartbeat
        REBALANCING     // Temporarily unavailable during rebalance
    }
    
    // Default constructor for Jackson
    public KafkaSlot() {
        this.status = SlotStatus.AVAILABLE;
    }
    
    @JsonCreator
    public KafkaSlot(@JsonProperty("topic") String topic, 
                     @JsonProperty("partition") int partition, 
                     @JsonProperty("groupId") String groupId) {
        this.topic = Objects.requireNonNull(topic, "topic cannot be null");
        this.partition = partition;
        this.groupId = Objects.requireNonNull(groupId, "groupId cannot be null");
        this.status = SlotStatus.AVAILABLE;
    }
    
    // Copy constructor
    public KafkaSlot(KafkaSlot other) {
        this.topic = other.topic;
        this.partition = other.partition;
        this.groupId = other.groupId;
        this.assignedEngine = other.assignedEngine;
        this.assignedAt = other.assignedAt;
        this.lastHeartbeat = other.lastHeartbeat;
        this.status = other.status;
    }
    
    /**
     * Create a unique identifier for this slot.
     */
    public String getSlotId() {
        return String.format("%s:%s:%d", topic, groupId, partition);
    }
    
    /**
     * Assign this slot to an engine.
     */
    public void assign(String engineInstanceId) {
        this.assignedEngine = engineInstanceId;
        this.assignedAt = Instant.now();
        this.lastHeartbeat = this.assignedAt;
        this.status = SlotStatus.ASSIGNED;
    }
    
    /**
     * Release this slot.
     */
    public void release() {
        this.assignedEngine = null;
        this.assignedAt = null;
        this.lastHeartbeat = null;
        this.status = SlotStatus.AVAILABLE;
    }
    
    /**
     * Update heartbeat timestamp.
     */
    public void updateHeartbeat() {
        this.lastHeartbeat = Instant.now();
        if (this.status == SlotStatus.HEARTBEAT_EXPIRED) {
            this.status = SlotStatus.ASSIGNED;
        }
    }
    
    /**
     * Check if heartbeat has expired.
     */
    public boolean isHeartbeatExpired(long heartbeatTimeoutSeconds) {
        if (lastHeartbeat == null || status != SlotStatus.ASSIGNED) {
            return false;
        }
        return Instant.now().minusSeconds(heartbeatTimeoutSeconds).isAfter(lastHeartbeat);
    }
    
    // Getters and setters
    public String getTopic() {
        return topic;
    }
    
    public void setTopic(String topic) {
        this.topic = topic;
    }
    
    public int getPartition() {
        return partition;
    }
    
    public void setPartition(int partition) {
        this.partition = partition;
    }
    
    public String getGroupId() {
        return groupId;
    }
    
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }
    
    public String getAssignedEngine() {
        return assignedEngine;
    }
    
    public void setAssignedEngine(String assignedEngine) {
        this.assignedEngine = assignedEngine;
    }
    
    public Instant getAssignedAt() {
        return assignedAt;
    }
    
    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }
    
    public Instant getLastHeartbeat() {
        return lastHeartbeat;
    }
    
    public void setLastHeartbeat(Instant lastHeartbeat) {
        this.lastHeartbeat = lastHeartbeat;
    }
    
    public SlotStatus getStatus() {
        return status;
    }
    
    public void setStatus(SlotStatus status) {
        this.status = status;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KafkaSlot kafkaSlot = (KafkaSlot) o;
        return partition == kafkaSlot.partition &&
               Objects.equals(topic, kafkaSlot.topic) &&
               Objects.equals(groupId, kafkaSlot.groupId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(topic, partition, groupId);
    }
    
    @Override
    public String toString() {
        return "KafkaSlot{" +
               "topic='" + topic + '\'' +
               ", partition=" + partition +
               ", groupId='" + groupId + '\'' +
               ", assignedEngine='" + assignedEngine + '\'' +
               ", status=" + status +
               '}';
    }
}