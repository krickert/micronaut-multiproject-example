package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Response from validation operations.
 */
@Serdeable
@Schema(description = "Validation result")
public record ValidationResponse(
    @Schema(description = "Whether validation passed")
    boolean valid,
    
    @Schema(description = "Validation errors if any")
    List<ValidationError> errors,
    
    @Schema(description = "Validation warnings (non-blocking)")
    List<ValidationWarning> warnings
) {
    
    @Serdeable
    @Schema(description = "A validation error")
    public record ValidationError(
        @Schema(description = "Field or component with error", example = "steps[0].module")
        String field,
        
        @Schema(description = "Error message", example = "Module 'unknown-module' not found")
        String message,
        
        @Schema(description = "Error code for programmatic handling", example = "MODULE_NOT_FOUND")
        String code
    ) {}
    
    @Serdeable
    @Schema(description = "A validation warning")
    public record ValidationWarning(
        @Schema(description = "Field or component with warning")
        String field,
        
        @Schema(description = "Warning message")
        String message,
        
        @Schema(description = "Warning code")
        String code
    ) {}
}