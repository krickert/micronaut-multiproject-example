package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Environment verification status.
 */
@Serdeable
@Schema(description = "Environment status and readiness")
public record EnvironmentStatus(
    @Schema(description = "Overall environment health", allowableValues = {"ready", "degraded", "not-ready"})
    String status,
    
    @Schema(description = "Check timestamp")
    Instant checkedAt,
    
    @Schema(description = "Individual service statuses")
    List<ServiceStatus> services,
    
    @Schema(description = "Any issues found")
    List<String> issues,
    
    @Schema(description = "Environment readiness for testing")
    boolean readyForTesting
) {
    
    @Serdeable
    @Schema(description = "Status of an individual service")
    public record ServiceStatus(
        @Schema(description = "Service name", example = "consul")
        String name,
        
        @Schema(description = "Service type", example = "infrastructure")
        String type,
        
        @Schema(description = "Service status", allowableValues = {"running", "stopped", "unknown"})
        String status,
        
        @Schema(description = "Service version")
        String version,
        
        @Schema(description = "Service endpoint")
        String endpoint,
        
        @Schema(description = "Health check passed")
        boolean healthy
    ) {}
}