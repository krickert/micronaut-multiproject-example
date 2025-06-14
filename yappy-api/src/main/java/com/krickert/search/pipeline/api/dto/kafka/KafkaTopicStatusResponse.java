package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO containing detailed status information about a Kafka topic.
 */
@Builder
@Introspected
@Serdeable
public record KafkaTopicStatusResponse(
        String topicName,
        String healthStatus,
        Integer partitionCount,
        Integer replicationFactor,
        Long largestOffset,
        Long totalLag,
        String listenerStatus,
        Map<Integer, PartitionStatus> partitionStatuses,
        Map<String, ConsumerGroupStatus> consumerGroups,
        Instant timestamp
) {
    /**
     * Status information for a single partition.
     */
    @Builder
    @Introspected
    @Serdeable
    public record PartitionStatus(
            Integer partitionId,
            Integer leader,
            Integer[] replicas,
            Integer[] inSyncReplicas,
            Long startOffset,
            Long endOffset
    ) {}
    
    /**
     * Status information for a consumer group on this topic.
     */
    @Builder
    @Introspected
    @Serdeable
    public record ConsumerGroupStatus(
            String groupId,
            String state,
            Long totalLag,
            Map<Integer, Long> partitionLags,
            Map<Integer, Long> committedOffsets
    ) {}
}