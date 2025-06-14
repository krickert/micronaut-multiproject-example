package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * A module configuration template.
 */
@Serdeable
@Schema(description = "Module configuration template")
public record ModuleTemplate(
    @Schema(description = "Template ID", example = "tika-basic")
    String id,
    
    @Schema(description = "Module type this template is for", example = "tika-parser")
    String moduleType,
    
    @Schema(description = "Template name", example = "Basic Tika Configuration")
    String name,
    
    @Schema(description = "Template description")
    String description,
    
    @Schema(description = "Use cases for this template")
    List<String> useCases,
    
    @Schema(description = "Default configuration")
    Map<String, Object> defaultConfig,
    
    @Schema(description = "Configuration parameters")
    List<ConfigParameter> parameters
) {
    
    @Serdeable
    @Schema(description = "A configuration parameter")
    public record ConfigParameter(
        @Schema(description = "Parameter name", example = "maxFileSize")
        String name,
        
        @Schema(description = "Parameter description")
        String description,
        
        @Schema(description = "Parameter type", allowableValues = {"string", "number", "boolean", "array", "object"})
        String type,
        
        @Schema(description = "Default value")
        Object defaultValue,
        
        @Schema(description = "Whether this parameter is required")
        boolean required,
        
        @Schema(description = "Validation constraints")
        Map<String, Object> constraints
    ) {}
}