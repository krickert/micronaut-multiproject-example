package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.util.Map;

/**
 * Response from testing a module.
 */
@Serdeable
@Schema(description = "Module test response")
public record ModuleTestResponse(
    @Schema(description = "Whether the test succeeded")
    boolean success,
    
    @Schema(description = "Processing duration")
    Duration processingTime,
    
    @Schema(description = "Output from the module")
    Map<String, Object> output,
    
    @Schema(description = "Output content type", example = "text/plain")
    String outputType,
    
    @Schema(description = "Module that processed the request")
    String moduleId,
    
    @Schema(description = "Instance that handled the request")
    String instanceId,
    
    @Schema(description = "Any error message")
    String error,
    
    @Schema(description = "Processing metadata")
    Map<String, String> metadata
) {}