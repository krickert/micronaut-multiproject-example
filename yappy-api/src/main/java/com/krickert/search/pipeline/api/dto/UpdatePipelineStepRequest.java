package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request to update an existing step in a pipeline.
 * All fields are optional - only provided fields will be updated.
 */
@Serdeable
@Schema(description = "Request to update an existing pipeline step")
public record UpdatePipelineStepRequest(
    @Schema(description = "Updated module to use", example = "embedder-v2")
    String module,
    
    @Schema(description = "Updated step configuration")
    Map<String, Object> config,
    
    @Schema(description = "Updated next step connections")
    List<String> next,
    
    @Schema(description = "Updated previous step connections")
    List<String> previous,
    
    @Schema(description = "Updated position hint")
    Integer position
) {}