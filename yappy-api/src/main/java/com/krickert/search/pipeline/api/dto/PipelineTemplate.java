package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A pre-built pipeline template.
 */
@Serdeable
@Schema(description = "A reusable pipeline template")
public record PipelineTemplate(
    @Schema(description = "Template ID", example = "nlp-basic")
    String id,
    
    @Schema(description = "Template name", example = "Basic NLP Pipeline")
    String name,
    
    @Schema(description = "What this template does")
    String description,
    
    @Schema(description = "Use cases for this template")
    List<String> useCases,
    
    @Schema(description = "Template category", example = "text-processing")
    String category,
    
    @Schema(description = "Pipeline steps in this template")
    List<CreatePipelineRequest.StepDefinition> steps,
    
    @Schema(description = "Variables that can be customized")
    List<TemplateVariable> variables,
    
    @Schema(description = "Default configuration")
    Map<String, Object> defaultConfig
) {
    
    @Serdeable
    @Schema(description = "A customizable template variable")
    public record TemplateVariable(
        @Schema(description = "Variable name", example = "chunkSize")
        String name,
        
        @Schema(description = "Variable description")
        String description,
        
        @Schema(description = "Variable type", allowableValues = {"string", "number", "boolean"})
        String type,
        
        @Schema(description = "Default value")
        Object defaultValue,
        
        @Schema(description = "Whether this variable is required")
        boolean required
    ) {}
}