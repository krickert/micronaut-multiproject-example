package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Result of a validation operation.
 */
@Serdeable
@Schema(description = "Validation result details")
public record ValidationResult(
    @Schema(description = "Whether validation passed")
    boolean valid,
    
    @Schema(description = "Validation messages")
    List<ValidationMessage> messages,
    
    @Schema(description = "Validated object details")
    Map<String, Object> validatedObject,
    
    @Schema(description = "Validation context")
    String context
) {
    
    @Serdeable
    @Schema(description = "A validation message")
    public record ValidationMessage(
        @Schema(description = "Message severity", allowableValues = {"error", "warning", "info"})
        String severity,
        
        @Schema(description = "Field or path this message relates to")
        String field,
        
        @Schema(description = "Message text")
        String message,
        
        @Schema(description = "Validation rule that triggered this message")
        String rule
    ) {}
}