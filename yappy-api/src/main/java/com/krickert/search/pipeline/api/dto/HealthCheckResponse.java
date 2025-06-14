package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Health check response.
 */
@Serdeable
@Schema(description = "Health check response")
public record HealthCheckResponse(
    @Schema(description = "Service name")
    String serviceName,
    
    @Schema(description = "Health status", allowableValues = {"healthy", "unhealthy", "unknown"})
    String status,
    
    @Schema(description = "Check timestamp")
    Instant checkedAt,
    
    @Schema(description = "Response time")
    Duration responseTime,
    
    @Schema(description = "Service version")
    String version,
    
    @Schema(description = "Additional health details")
    Map<String, Object> details,
    
    @Schema(description = "Error message if unhealthy")
    String error
) {}