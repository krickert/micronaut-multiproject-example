package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO containing Kafka metrics.
 */
@Builder
@Introspected
@Serdeable
public record KafkaMetricsResponse(
        ProducerMetrics producerMetrics,
        ConsumerMetrics consumerMetrics,
        TopicMetrics topicMetrics,
        Map<String, Object> customMetrics,
        Instant timestamp
) {
    /**
     * Producer-related metrics.
     */
    @Builder
    @Introspected
    @Serdeable
    public record ProducerMetrics(
            Long totalRecordsSent,
            Long totalBytesSent,
            Double recordSendRate,
            Double byteSendRate,
            Long recordSendErrors,
            Long batchSizeAvg,
            Long compressionRateAvg,
            Map<String, Long> recordsSentPerTopic
    ) {}
    
    /**
     * Consumer-related metrics.
     */
    @Builder
    @Introspected
    @Serdeable
    public record ConsumerMetrics(
            Long totalRecordsConsumed,
            Long totalBytesConsumed,
            Double recordConsumeRate,
            Double byteConsumeRate,
            Long fetchErrors,
            Long commitLatencyAvg,
            Map<String, Long> recordsConsumedPerTopic,
            Map<String, Long> lagPerConsumerGroup
    ) {}
    
    /**
     * Topic-related metrics.
     */
    @Builder
    @Introspected
    @Serdeable
    public record TopicMetrics(
            Long totalTopics,
            Long totalPartitions,
            Map<String, TopicSummary> topicSummaries
    ) {}
    
    /**
     * Summary metrics for a single topic.
     */
    @Builder
    @Introspected
    @Serdeable
    public record TopicSummary(
            String topicName,
            Integer partitionCount,
            Long totalMessages,
            Long totalBytes,
            Double messagesPerSecond,
            Double bytesPerSecond
    ) {}
}