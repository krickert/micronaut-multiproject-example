package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Module health status information.
 */
@Serdeable
@Schema(description = "Module health status")
public record ModuleHealthStatus(
    @Schema(description = "Module service ID")
    String serviceId,
    
    @Schema(description = "Overall health status", allowableValues = {"healthy", "degraded", "unhealthy", "unknown"})
    String status,
    
    @Schema(description = "Health check timestamp")
    Instant checkedAt,
    
    @Schema(description = "Response time in milliseconds")
    long responseTimeMs,
    
    @Schema(description = "Individual instance health")
    List<InstanceHealth> instances,
    
    @Schema(description = "Health check details")
    Map<String, Object> details,
    
    @Schema(description = "Any error messages")
    String error
) {
    
    @Serdeable
    @Schema(description = "Health of a single module instance")
    public record InstanceHealth(
        @Schema(description = "Instance ID")
        String instanceId,
        
        @Schema(description = "Instance address")
        String address,
        
        @Schema(description = "Instance health", allowableValues = {"healthy", "unhealthy"})
        String status,
        
        @Schema(description = "Last check time")
        Instant lastCheck,
        
        @Schema(description = "Instance metadata")
        Map<String, String> metadata
    ) {}
}