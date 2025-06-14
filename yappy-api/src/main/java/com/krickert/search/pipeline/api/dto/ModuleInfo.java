package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Information about a registered module.
 */
@Serdeable
@Schema(description = "Registered module information")
public record ModuleInfo(
    @Schema(description = "Module service ID", example = "tika-parser")
    String serviceId,
    
    @Schema(description = "Module display name", example = "Apache Tika Parser")
    String name,
    
    @Schema(description = "Module description")
    String description,
    
    @Schema(description = "Module version", example = "1.0.0")
    String version,
    
    @Schema(description = "Number of running instances")
    int instances,
    
    @Schema(description = "Health status", allowableValues = {"healthy", "degraded", "unhealthy"})
    String health,
    
    @Schema(description = "Module capabilities/features")
    List<String> capabilities,
    
    @Schema(description = "Input data types this module accepts", example = "[\"application/pdf\", \"text/plain\"]")
    List<String> inputTypes,
    
    @Schema(description = "Output data types this module produces", example = "[\"text/plain\"]")
    List<String> outputTypes,
    
    @Schema(description = "Configuration schema for this module")
    Map<String, Object> configSchema,
    
    @Schema(description = "When this module was registered")
    Instant registeredAt,
    
    @Schema(description = "Last health check time")
    Instant lastHealthCheck
) {}