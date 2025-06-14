package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.Map;

/**
 * Request to generate test documents.
 */
@Serdeable
@Schema(description = "Test document generation request")
public record TestDataGenerationRequest(
    @NotBlank
    @Schema(description = "Document type", example = "pdf", allowableValues = {"pdf", "text", "html", "json"})
    String documentType,
    
    @Min(1)
    @Max(1000)
    @Schema(description = "Number of documents to generate", example = "10")
    int count,
    
    @Schema(description = "Size category", example = "medium", allowableValues = {"small", "medium", "large"})
    String sizeCategory,
    
    @Schema(description = "Content template or pattern")
    String contentTemplate,
    
    @Schema(description = "Generation parameters")
    Map<String, Object> parameters,
    
    @Schema(description = "Random seed for reproducible generation")
    Long seed
) {}