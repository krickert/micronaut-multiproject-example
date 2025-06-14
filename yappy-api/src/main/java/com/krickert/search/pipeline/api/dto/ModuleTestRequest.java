package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request to test a module with sample data.
 */
@Serdeable
@Schema(description = "Module test request")
public record ModuleTestRequest(
    @NotBlank
    @Schema(description = "Test content", example = "Sample text to process")
    String content,
    
    @Schema(description = "Content type", example = "text/plain")
    String contentType,
    
    @Schema(description = "Module-specific configuration for this test")
    Map<String, Object> config,
    
    @Schema(description = "Test metadata")
    Map<String, String> metadata,
    
    @Schema(description = "Timeout in seconds", example = "10")
    Integer timeoutSeconds
) {}