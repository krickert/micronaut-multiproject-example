package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Real-time status of a YAPPY cluster.
 */
@Serdeable
@Schema(description = "Cluster operational status")
public record ClusterStatus(
    @Schema(description = "Cluster name")
    String clusterName,
    
    @Schema(description = "Overall cluster health", allowableValues = {"UP", "DEGRADED", "DOWN"})
    HealthStatus overallHealth,
    
    @Schema(description = "Number of active pipelines")
    int activePipelines,
    
    @Schema(description = "Number of registered modules")
    int registeredModules,
    
    @Schema(description = "Health status of each module")
    Map<String, ModuleStatus> moduleStatuses,
    
    @Schema(description = "Active Kafka listeners")
    List<KafkaListenerStatus> kafkaListeners,
    
    @Schema(description = "Last status check timestamp")
    Instant lastChecked
) {
    
    public enum HealthStatus {
        UP, DEGRADED, DOWN
    }
    
    @Serdeable
    @Schema(description = "Module health status")
    public record ModuleStatus(
        @Schema(description = "Module ID")
        String moduleId,
        
        @Schema(description = "Service name in Consul")
        String serviceName,
        
        @Schema(description = "Health status")
        HealthStatus status,
        
        @Schema(description = "Number of healthy instances")
        int healthyInstances,
        
        @Schema(description = "Total number of instances")
        int totalInstances,
        
        @Schema(description = "Last health check time")
        Instant lastHealthCheck,
        
        @Schema(description = "Error message if unhealthy")
        String errorMessage
    ) {}
    
    @Serdeable
    @Schema(description = "Kafka listener status")
    public record KafkaListenerStatus(
        @Schema(description = "Topic name")
        String topic,
        
        @Schema(description = "Consumer group ID")
        String groupId,
        
        @Schema(description = "Pipeline ID this listener serves")
        String pipelineId,
        
        @Schema(description = "Is the listener active")
        boolean active,
        
        @Schema(description = "Current lag across all partitions")
        long totalLag,
        
        @Schema(description = "Messages processed in last minute")
        long messagesPerMinute
    ) {}
}