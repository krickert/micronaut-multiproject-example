package com.krickert.search.config.consul.model;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Represents a YAPPY cluster configuration.
 */
@Serdeable
@Schema(description = "YAPPY cluster information")
public record ClusterInfo(
    @NotBlank
    @Schema(description = "Unique cluster name", example = "dev-cluster")
    String name,
    
    @Schema(description = "Human-readable description", example = "Development cluster for testing")
    String description,
    
    @NotEmpty
    @Schema(description = "List of pipeline IDs configured for this cluster")
    List<String> pipelineIds,
    
    @Schema(description = "Allowed Kafka topics for this cluster")
    List<String> allowedKafkaTopics,
    
    @Schema(description = "Allowed gRPC services for this cluster")
    List<String> allowedGrpcServices,
    
    @Schema(description = "Module configurations by module ID")
    Map<String, ModuleConfig> moduleConfigs,
    
    @Schema(description = "When the cluster was created")
    Instant createdAt,
    
    @Schema(description = "When the cluster was last updated")
    Instant updatedAt,
    
    @Schema(description = "Additional metadata")
    Map<String, Object> metadata
) {
    
    /**
     * Module configuration within a cluster.
     */
    @Serdeable
    @Schema(description = "Module configuration")
    public record ModuleConfig(
        @Schema(description = "Module service URL", example = "dns:///tika-parser:50051")
        String moduleUrl,
        
        @Schema(description = "Connection configuration")
        ConnectionConfig connectionConfig
    ) {}
    
    /**
     * Connection configuration for a module.
     */
    @Serdeable
    @Schema(description = "Connection configuration for a module")
    public record ConnectionConfig(
        @Schema(description = "Maximum retry attempts", example = "3")
        Integer maxRetryAttempts,
        
        @Schema(description = "Retry policy", example = "EXPONENTIAL_BACKOFF")
        String retryPolicy,
        
        @Schema(description = "Deadline in milliseconds", example = "30000")
        Long deadlineMs
    ) {}
}