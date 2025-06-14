package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Request to update an existing pipeline.
 * All fields are optional - only provided fields will be updated.
 */
@Serdeable
@Schema(description = "Request to update a pipeline configuration")
public record UpdatePipelineRequest(
    @Schema(description = "Updated name", example = "Enhanced Document Pipeline")
    String name,
    
    @Schema(description = "Updated description")
    String description,
    
    @Schema(description = "Updated processing steps")
    List<CreatePipelineRequest.StepDefinition> steps,
    
    @Schema(description = "Updated tags")
    List<String> tags,
    
    @Schema(description = "Set pipeline active/inactive")
    Boolean active,
    
    @Schema(description = "Additional configuration updates")
    Map<String, Object> config
) {}