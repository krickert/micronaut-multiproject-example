package com.krickert.search.pipeline.api.dto.kafka;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO containing Kafka cluster health information.
 */
@Builder
@Introspected
@Serdeable
public record KafkaHealthResponse(
        String status,  // HEALTHY, DEGRADED, UNHEALTHY
        Integer availableBrokers,
        Integer expectedBrokers,
        Long totalTopics,
        Long totalConsumerGroups,
        List<BrokerHealth> brokers,
        Map<String, String> clusterMetadata,
        List<String> issues,
        Instant timestamp
) {
    /**
     * Health information for a single broker.
     */
    @Builder
    @Introspected
    @Serdeable
    public record BrokerHealth(
            Integer brokerId,
            String host,
            Integer port,
            String status,
            Long inFlightRequests,
            Long producedRequestsPerSec,
            Long fetchRequestsPerSec
    ) {}
}