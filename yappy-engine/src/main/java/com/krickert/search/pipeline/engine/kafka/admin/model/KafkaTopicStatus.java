package com.krickert.search.pipeline.engine.kafka.admin.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A Java Record that represents the status of a Kafka topic.
 * This record is designed to be serialized to JSON for use in operational dashboards.
 * 
 * <p>The record includes information about:</p>
 * <ul>
 *   <li>Basic topic information (name, health status, last checked time)</li>
 *   <li>Partition information (count, replication factor)</li>
 *   <li>Offset information (per partition, largest offset)</li>
 *   <li>Consumer group information (ID, offsets, lag per partition, total lag)</li>
 *   <li>Listener status (RECEIVING, PAUSED, STOPPED, ERROR, UNKNOWN)</li>
 *   <li>Metrics information (map of metric names to values)</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Create an ObjectMapper with JavaTimeModule for handling Instant
 * ObjectMapper objectMapper = new ObjectMapper();
 * objectMapper.registerModule(new JavaTimeModule());
 * 
 * // Serialize to JSON
 * String json = objectMapper.writeValueAsString(status);
 * 
 * // Deserialize from JSON
 * KafkaTopicStatus deserialized = objectMapper.readValue(json, KafkaTopicStatus.class);
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaTopicStatus(
    // Basic topic information
    String topicName,
    boolean isHealthy,
    String healthStatus,
    Instant lastCheckedTime,

    // Partition information
    int partitionCount,
    short replicationFactor,

    // Offset information
    Map<Integer, Long> partitionOffsets,
    long largestOffset,

    // Consumer group information
    String consumerGroupId,
    Map<Integer, Long> consumerGroupOffsets,
    Map<Integer, Long> lagPerPartition,
    long totalLag,

    // Listener status
    ListenerStatus listenerStatus,

    // Metrics information
    Map<String, Double> metrics
) {
    /**
     * Enum representing the status of a Kafka listener.
     */
    public enum ListenerStatus {
        RECEIVING,
        PAUSED,
        STOPPED,
        ERROR,
        UNKNOWN
    }

    /**
     * Builder class for KafkaTopicStatus.
     */
    public static class Builder {
        private String topicName;
        private boolean isHealthy = true;
        private String healthStatus = "OK";
        private Instant lastCheckedTime = Instant.now();
        private int partitionCount;
        private short replicationFactor;
        private Map<Integer, Long> partitionOffsets;
        private long largestOffset;
        private String consumerGroupId;
        private Map<Integer, Long> consumerGroupOffsets;
        private Map<Integer, Long> lagPerPartition;
        private long totalLag;
        private ListenerStatus listenerStatus = ListenerStatus.UNKNOWN;
        private Map<String, Double> metrics;

        public Builder topicName(String topicName) {
            this.topicName = topicName;
            return this;
        }

        public Builder isHealthy(boolean isHealthy) {
            this.isHealthy = isHealthy;
            return this;
        }

        public Builder healthStatus(String healthStatus) {
            this.healthStatus = healthStatus;
            return this;
        }

        public Builder lastCheckedTime(Instant lastCheckedTime) {
            this.lastCheckedTime = lastCheckedTime;
            return this;
        }

        public Builder partitionCount(int partitionCount) {
            this.partitionCount = partitionCount;
            return this;
        }

        public Builder replicationFactor(short replicationFactor) {
            this.replicationFactor = replicationFactor;
            return this;
        }

        public Builder partitionOffsets(Map<Integer, Long> partitionOffsets) {
            this.partitionOffsets = partitionOffsets;
            return this;
        }

        public Builder largestOffset(long largestOffset) {
            this.largestOffset = largestOffset;
            return this;
        }

        public Builder consumerGroupId(String consumerGroupId) {
            this.consumerGroupId = consumerGroupId;
            return this;
        }

        public Builder consumerGroupOffsets(Map<Integer, Long> consumerGroupOffsets) {
            this.consumerGroupOffsets = consumerGroupOffsets;
            return this;
        }

        public Builder lagPerPartition(Map<Integer, Long> lagPerPartition) {
            this.lagPerPartition = lagPerPartition;
            return this;
        }

        public Builder totalLag(long totalLag) {
            this.totalLag = totalLag;
            return this;
        }

        public Builder listenerStatus(ListenerStatus listenerStatus) {
            this.listenerStatus = listenerStatus;
            return this;
        }

        public Builder metrics(Map<String, Double> metrics) {
            this.metrics = metrics;
            return this;
        }

        public KafkaTopicStatus build() {
            return new KafkaTopicStatus(
                topicName,
                isHealthy,
                healthStatus,
                lastCheckedTime,
                partitionCount,
                replicationFactor,
                partitionOffsets,
                largestOffset,
                consumerGroupId,
                consumerGroupOffsets,
                lagPerPartition,
                totalLag,
                listenerStatus,
                metrics
            );
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
