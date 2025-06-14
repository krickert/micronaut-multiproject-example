package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.Map;

/**
 * Request to create a pipeline from a template.
 */
@Serdeable
@Schema(description = "Request to create a pipeline from a template")
public record CreateFromTemplateRequest(
    @NotBlank
    @Schema(description = "Template ID to use", example = "nlp-basic")
    String templateId,
    
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "ID must be lowercase letters, numbers, and hyphens only")
    @Schema(description = "ID for the new pipeline", example = "my-nlp-pipeline")
    String pipelineId,
    
    @NotBlank
    @Schema(description = "Name for the new pipeline", example = "My NLP Pipeline")
    String pipelineName,
    
    @Schema(description = "Variable values to customize the template")
    Map<String, Object> variables,
    
    @Schema(description = "Additional configuration overrides")
    Map<String, Object> configOverrides
) {}