package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request to create test data.
 */
@Serdeable
@Schema(description = "Request to create test data")
public record TestDataRequest(
    @NotBlank
    @Schema(description = "Type of test data", example = "document")
    String dataType,
    
    @Schema(description = "Number of items to generate", example = "10")
    int count,
    
    @Schema(description = "Test data parameters")
    Map<String, Object> parameters,
    
    @Schema(description = "Output format", example = "json")
    String format
) {}