package com.krickert.search.pipeline.kafka.admin;

import org.apache.kafka.common.TopicPartition;
import java.util.Map;
import java.util.Objects;

/**
 * Parameters for resetting consumer group offsets for a topic.
 */
public class OffsetResetParameters {
    private final OffsetResetStrategy strategy;
    private final Map<TopicPartition, Long> specificOffsets; // Used for TO_SPECIFIC_OFFSETS
    private final Long timestamp; // Used for TO_TIMESTAMP

    private OffsetResetParameters(Builder builder) {
        this.strategy = Objects.requireNonNull(builder.strategy, "Strategy cannot be null");
        this.specificOffsets = builder.specificOffsets;
        this.timestamp = builder.timestamp;

        // Validation
        if (strategy == OffsetResetStrategy.TO_SPECIFIC_OFFSETS && (specificOffsets == null || specificOffsets.isEmpty())) {
            throw new IllegalArgumentException("Specific offsets must be provided for TO_SPECIFIC_OFFSETS strategy.");
        }
        if (strategy == OffsetResetStrategy.TO_TIMESTAMP && timestamp == null) {
            throw new IllegalArgumentException("Timestamp must be provided for TO_TIMESTAMP strategy.");
        }
    }

    public OffsetResetStrategy getStrategy() {
        return strategy;
    }

    public Map<TopicPartition, Long> getSpecificOffsets() {
        return specificOffsets;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public static Builder builder(OffsetResetStrategy strategy) {
        return new Builder(strategy);
    }

    public static class Builder {
        private final OffsetResetStrategy strategy;
        private Map<TopicPartition, Long> specificOffsets;
        private Long timestamp;

        public Builder(OffsetResetStrategy strategy) {
            this.strategy = strategy;
        }

        public Builder specificOffsets(Map<TopicPartition, Long> specificOffsets) {
            this.specificOffsets = specificOffsets;
            return this;
        }

        public Builder timestamp(Long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public OffsetResetParameters build() {
            return new OffsetResetParameters(this);
        }
    }
}