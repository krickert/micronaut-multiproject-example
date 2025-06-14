package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO containing consumer lag information.
 */
@Builder
@Introspected
@Serdeable
public record KafkaConsumerLagResponse(
        String consumerGroupId,
        Long totalLag,
        Map<String, TopicLag> topicLags,
        Instant timestamp
) {
    /**
     * Lag information for a specific topic.
     */
    @Builder
    @Introspected
    @Serdeable
    public record TopicLag(
            String topicName,
            Long totalLag,
            Map<Integer, PartitionLag> partitionLags
    ) {}
    
    /**
     * Lag information for a specific partition.
     */
    @Builder
    @Introspected
    @Serdeable
    public record PartitionLag(
            Integer partitionId,
            Long lag,
            Long committedOffset,
            Long endOffset
    ) {}
}