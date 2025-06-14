package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request to test a pipeline with sample data.
 */
@Serdeable
@Schema(description = "Request to test a pipeline")
public record TestPipelineRequest(
    @NotBlank
    @Schema(description = "Test document content or URL", example = "Hello world, this is a test document.")
    String content,
    
    @Schema(description = "Content type", example = "text/plain")
    String contentType,
    
    @Schema(description = "Optional source metadata")
    Map<String, String> metadata,
    
    @Schema(description = "Timeout in seconds", example = "30")
    Integer timeoutSeconds
) {}