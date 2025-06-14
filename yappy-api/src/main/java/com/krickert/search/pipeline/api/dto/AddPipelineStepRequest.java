package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Request to add a new step to an existing pipeline.
 */
@Serdeable
@Schema(description = "Request to add a new step to an existing pipeline")
public record AddPipelineStepRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Step ID must be lowercase letters, numbers, and hyphens only")
    @Schema(description = "Unique step identifier", example = "new-enrichment-step")
    String stepId,
    
    @NotBlank
    @Schema(description = "Module to use for processing", example = "embedder")
    String module,
    
    @Schema(description = "Step-specific configuration", example = "{\"model\": \"text-embedding-ada-002\"}")
    Map<String, Object> config,
    
    @Schema(description = "Next step(s) to connect to", example = "[\"final-step\"]")
    List<String> next,
    
    @Schema(description = "Previous step(s) to connect from", example = "[\"chunker-step\"]", 
            required = true)
    List<String> previous,
    
    @Schema(description = "Position hint for UI rendering", example = "2")
    Integer position
) {}