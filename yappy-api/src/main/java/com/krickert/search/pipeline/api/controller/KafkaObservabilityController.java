package com.krickert.search.pipeline.api.controller;

import com.krickert.search.orchestrator.kafka.admin.KafkaAdminService;
import com.krickert.search.orchestrator.kafka.admin.KafkaTopicStatusService;
import com.krickert.search.orchestrator.kafka.admin.model.KafkaTopicStatus;
import com.krickert.search.pipeline.api.dto.kafka.*;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * REST controller for Kafka observability operations.
 * Provides metrics, consumer lag monitoring, and health checks.
 */
@Controller("/api/v1/kafka/observability")
@Tag(name = "Kafka Observability", description = "Kafka metrics, consumer lag, and health monitoring")
public class KafkaObservabilityController {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaObservabilityController.class);

    private final KafkaTopicStatusService topicStatusService;
    private final KafkaAdminService adminService;

    @Inject
    public KafkaObservabilityController(
            KafkaTopicStatusService topicStatusService,
            KafkaAdminService adminService) {
        this.topicStatusService = topicStatusService;
        this.adminService = adminService;
    }

    /**
     * Get detailed status for a specific topic.
     */
    @Get("/topics/{topicName}/status")
    @Operation(
        summary = "Get topic status",
        description = "Gets detailed status information for a specific Kafka topic"
    )
    @ApiResponse(responseCode = "200", description = "Topic status retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Topic not found")
    @ApiResponse(responseCode = "500", description = "Failed to retrieve topic status")
    public HttpResponse<KafkaTopicStatusResponse> getTopicStatus(@PathVariable String topicName) {
        LOG.info("Getting status for topic: {}", topicName);
        
        try {
            KafkaTopicStatus status = topicStatusService.getTopicStatus(topicName);
            
            // Convert to response DTO
            KafkaTopicStatusResponse response = KafkaTopicStatusResponse.builder()
                .topicName(status.topicName())
                .healthStatus(status.healthStatus())
                .partitionCount(status.partitionCount())
                .replicationFactor((int) status.replicationFactor())
                .largestOffset(status.largestOffset())
                .totalLag(status.totalLag())
                .listenerStatus(status.listenerStatus().name())
                .partitionStatuses(convertPartitionStatuses(status))
                .consumerGroups(convertConsumerGroups(status))
                .timestamp(Instant.now())
                .build();
            
            return HttpResponse.ok(response);
            
        } catch (Exception e) {
            LOG.error("Failed to get status for topic {}: {}", topicName, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Get status for multiple topics.
     */
    @Post("/topics/status")
    @Operation(
        summary = "Get multiple topic statuses",
        description = "Gets status information for multiple Kafka topics"
    )
    @ApiResponse(responseCode = "200", description = "Topic statuses retrieved successfully")
    @ApiResponse(responseCode = "500", description = "Failed to retrieve topic statuses")
    public HttpResponse<Map<String, KafkaTopicStatusResponse>> getMultipleTopicStatuses(
            @Body List<String> topicNames) {
        LOG.info("Getting status for {} topics", topicNames.size());
        
        try {
            Map<String, KafkaTopicStatus> statuses = topicStatusService.getMultipleTopicStatus(topicNames);
            
            Map<String, KafkaTopicStatusResponse> responses = statuses.entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    entry -> convertToStatusResponse(entry.getValue())
                ));
            
            return HttpResponse.ok(responses);
            
        } catch (Exception e) {
            LOG.error("Failed to get status for topics: {}", e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Get consumer lag for a specific consumer group.
     */
    @Get("/consumer-groups/{groupId}/lag")
    @Operation(
        summary = "Get consumer group lag",
        description = "Gets lag information for a specific consumer group across all topics"
    )
    @ApiResponse(responseCode = "200", description = "Consumer lag retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Consumer group not found")
    @ApiResponse(responseCode = "500", description = "Failed to retrieve consumer lag")
    public HttpResponse<KafkaConsumerLagResponse> getConsumerGroupLag(@PathVariable String groupId) {
        LOG.info("Getting lag for consumer group: {}", groupId);
        
        try {
            // Get all topics
            Set<String> topics = adminService.listTopics();
            Map<String, KafkaConsumerLagResponse.TopicLag> topicLags = new HashMap<>();
            long totalLag = 0;
            
            // Get lag for each topic
            for (String topic : topics) {
                try {
                    Map<TopicPartition, Long> partitionLags = 
                        adminService.getConsumerLagPerPartitionAsync(groupId, topic).get();
                    
                    if (!partitionLags.isEmpty()) {
                        Map<Integer, KafkaConsumerLagResponse.PartitionLag> lagDetails = new HashMap<>();
                        long topicTotalLag = 0;
                        
                        for (Map.Entry<TopicPartition, Long> entry : partitionLags.entrySet()) {
                            TopicPartition tp = entry.getKey();
                            Long lag = entry.getValue();
                            topicTotalLag += lag;
                            
                            lagDetails.put(tp.partition(), KafkaConsumerLagResponse.PartitionLag.builder()
                                .partitionId(tp.partition())
                                .lag(lag)
                                .committedOffset(0L) // TODO: Get actual committed offset
                                .endOffset(0L) // TODO: Get actual end offset
                                .build());
                        }
                        
                        topicLags.put(topic, KafkaConsumerLagResponse.TopicLag.builder()
                            .topicName(topic)
                            .totalLag(topicTotalLag)
                            .partitionLags(lagDetails)
                            .build());
                        
                        totalLag += topicTotalLag;
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to get lag for topic {} group {}: {}", topic, groupId, e.getMessage());
                }
            }
            
            KafkaConsumerLagResponse response = KafkaConsumerLagResponse.builder()
                .consumerGroupId(groupId)
                .totalLag(totalLag)
                .topicLags(topicLags)
                .timestamp(Instant.now())
                .build();
            
            return HttpResponse.ok(response);
            
        } catch (Exception e) {
            LOG.error("Failed to get lag for consumer group {}: {}", groupId, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Get consumer lag for a specific topic and group.
     */
    @Get("/consumer-groups/{groupId}/topics/{topicName}/lag")
    @Operation(
        summary = "Get consumer lag for topic",
        description = "Gets lag information for a specific consumer group and topic"
    )
    @ApiResponse(responseCode = "200", description = "Consumer lag retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Consumer group or topic not found")
    @ApiResponse(responseCode = "500", description = "Failed to retrieve consumer lag")
    public HttpResponse<Map<String, Object>> getConsumerLagForTopic(
            @PathVariable String groupId,
            @PathVariable String topicName) {
        LOG.info("Getting lag for consumer group {} on topic {}", groupId, topicName);
        
        try {
            Long totalLag = adminService.getTotalConsumerLagAsync(groupId, topicName).get();
            Map<TopicPartition, Long> partitionLags = 
                adminService.getConsumerLagPerPartitionAsync(groupId, topicName).get();
            
            Map<String, Object> response = new HashMap<>();
            response.put("consumerGroupId", groupId);
            response.put("topicName", topicName);
            response.put("totalLag", totalLag);
            response.put("partitionLags", partitionLags.entrySet().stream()
                .collect(Collectors.toMap(
                    e -> String.valueOf(e.getKey().partition()),
                    Map.Entry::getValue
                )));
            response.put("timestamp", Instant.now());
            
            return HttpResponse.ok(response);
            
        } catch (Exception e) {
            LOG.error("Failed to get lag for group {} topic {}: {}", groupId, topicName, e.getMessage(), e);
            return HttpResponse.serverError();
        }
    }

    /**
     * Get Kafka cluster health.
     */
    @Get("/health")
    @Operation(
        summary = "Get Kafka cluster health",
        description = "Gets health information for the Kafka cluster"
    )
    @ApiResponse(responseCode = "200", description = "Health information retrieved successfully")
    @ApiResponse(responseCode = "503", description = "Kafka cluster is unhealthy")
    public HttpResponse<KafkaHealthResponse> getKafkaHealth() {
        LOG.info("Getting Kafka cluster health");
        
        try {
            int availableBrokers = adminService.getAvailableBrokerCount();
            Set<String> topics = adminService.listTopics();
            Set<String> consumerGroups = adminService.listConsumerGroupsAsync().get();
            
            // Determine health status
            String status = availableBrokers > 0 ? "HEALTHY" : "UNHEALTHY";
            List<String> issues = new ArrayList<>();
            
            if (availableBrokers == 0) {
                issues.add("No available brokers");
            }
            
            KafkaHealthResponse response = KafkaHealthResponse.builder()
                .status(status)
                .availableBrokers(availableBrokers)
                .expectedBrokers(availableBrokers) // TODO: Get expected from config
                .totalTopics((long) topics.size())
                .totalConsumerGroups((long) consumerGroups.size())
                .brokers(Collections.emptyList()) // TODO: Get detailed broker info
                .clusterMetadata(Map.of(
                    "version", "Unknown", // TODO: Get from cluster
                    "clusterId", "Unknown" // TODO: Get from cluster
                ))
                .issues(issues)
                .timestamp(Instant.now())
                .build();
            
            return status.equals("HEALTHY") ? HttpResponse.ok(response) : HttpResponse.serverError(response);
            
        } catch (Exception e) {
            LOG.error("Failed to get Kafka health: {}", e.getMessage(), e);
            
            KafkaHealthResponse errorResponse = KafkaHealthResponse.builder()
                .status("UNHEALTHY")
                .availableBrokers(0)
                .expectedBrokers(0)
                .totalTopics(0L)
                .totalConsumerGroups(0L)
                .brokers(Collections.emptyList())
                .clusterMetadata(Collections.emptyMap())
                .issues(List.of("Failed to connect to Kafka cluster: " + e.getMessage()))
                .timestamp(Instant.now())
                .build();
            
            return HttpResponse.serverError(errorResponse);
        }
    }

    /**
     * Get Kafka metrics.
     * Note: This is a placeholder. Real implementation would integrate with metrics registry.
     */
    @Get("/metrics")
    @Operation(
        summary = "Get Kafka metrics",
        description = "Gets metrics for Kafka producers, consumers, and topics"
    )
    @ApiResponse(responseCode = "200", description = "Metrics retrieved successfully")
    public HttpResponse<KafkaMetricsResponse> getKafkaMetrics() {
        LOG.info("Getting Kafka metrics");
        
        // TODO: Integrate with Micrometer metrics registry
        KafkaMetricsResponse response = KafkaMetricsResponse.builder()
            .producerMetrics(KafkaMetricsResponse.ProducerMetrics.builder()
                .totalRecordsSent(0L)
                .totalBytesSent(0L)
                .recordSendRate(0.0)
                .byteSendRate(0.0)
                .recordSendErrors(0L)
                .batchSizeAvg(0L)
                .compressionRateAvg(0L)
                .recordsSentPerTopic(Collections.emptyMap())
                .build())
            .consumerMetrics(KafkaMetricsResponse.ConsumerMetrics.builder()
                .totalRecordsConsumed(0L)
                .totalBytesConsumed(0L)
                .recordConsumeRate(0.0)
                .byteConsumeRate(0.0)
                .fetchErrors(0L)
                .commitLatencyAvg(0L)
                .recordsConsumedPerTopic(Collections.emptyMap())
                .lagPerConsumerGroup(Collections.emptyMap())
                .build())
            .topicMetrics(KafkaMetricsResponse.TopicMetrics.builder()
                .totalTopics(0L)
                .totalPartitions(0L)
                .topicSummaries(Collections.emptyMap())
                .build())
            .customMetrics(Map.of("note", "Metrics integration pending"))
            .timestamp(Instant.now())
            .build();
        
        return HttpResponse.ok(response);
    }

    // Helper methods
    
    private Map<Integer, KafkaTopicStatusResponse.PartitionStatus> convertPartitionStatuses(
            KafkaTopicStatus status) {
        if (status.partitionOffsets() == null) return Collections.emptyMap();
        
        Map<Integer, KafkaTopicStatusResponse.PartitionStatus> result = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : status.partitionOffsets().entrySet()) {
            result.put(entry.getKey(), KafkaTopicStatusResponse.PartitionStatus.builder()
                .partitionId(entry.getKey())
                .leader(-1) // Not available in current model
                .replicas(new Integer[0]) // Not available in current model
                .inSyncReplicas(new Integer[0]) // Not available in current model
                .startOffset(0L) // Not available in current model
                .endOffset(entry.getValue())
                .build());
        }
        return result;
    }
    
    private Map<String, KafkaTopicStatusResponse.ConsumerGroupStatus> convertConsumerGroups(
            KafkaTopicStatus status) {
        if (status.consumerGroupId() == null) return Collections.emptyMap();
        
        // Current model only supports single consumer group
        Map<String, KafkaTopicStatusResponse.ConsumerGroupStatus> result = new HashMap<>();
        result.put(status.consumerGroupId(), KafkaTopicStatusResponse.ConsumerGroupStatus.builder()
            .groupId(status.consumerGroupId())
            .state("UNKNOWN") // Not available in current model
            .totalLag(status.totalLag())
            .partitionLags(status.lagPerPartition())
            .committedOffsets(status.consumerGroupOffsets())
            .build());
        
        return result;
    }
    
    private KafkaTopicStatusResponse convertToStatusResponse(KafkaTopicStatus status) {
        return KafkaTopicStatusResponse.builder()
            .topicName(status.topicName())
            .healthStatus(status.healthStatus())
            .partitionCount(status.partitionCount())
            .replicationFactor((int) status.replicationFactor())
            .largestOffset(status.largestOffset())
            .totalLag(status.totalLag())
            .listenerStatus(status.listenerStatus().name())
            .partitionStatuses(convertPartitionStatuses(status))
            .consumerGroups(convertConsumerGroups(status))
            .timestamp(Instant.now())
            .build();
    }
}