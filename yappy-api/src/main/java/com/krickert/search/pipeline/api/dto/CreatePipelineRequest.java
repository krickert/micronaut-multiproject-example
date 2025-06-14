package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.Map;

/**
 * Simple request to create a new pipeline.
 */
@Serdeable
@Schema(description = "Request to create a new pipeline")
public record CreatePipelineRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "ID must be lowercase letters, numbers, and hyphens only")
    @Schema(description = "Unique pipeline ID", example = "document-enrichment")
    String id,
    
    @NotBlank
    @Schema(description = "Human-friendly name", example = "Document Enrichment Pipeline")
    String name,
    
    @Schema(description = "What this pipeline does", example = "Extracts text, chunks it, and generates embeddings")
    String description,
    
    @NotEmpty
    @Schema(description = "Processing steps in order")
    List<StepDefinition> steps,
    
    @Schema(description = "Optional tags for organization", example = "[\"nlp\", \"production\"]")
    List<String> tags,
    
    @Schema(description = "Additional configuration options")
    Map<String, Object> config
) {
    
    /**
     * Simple step definition for creating a pipeline.
     */
    @Serdeable
    @Schema(description = "Definition of a processing step")
    public record StepDefinition(
        @NotBlank
        @Pattern(regexp = "^[a-z0-9-]+$", message = "Step ID must be lowercase letters, numbers, and hyphens only")
        @Schema(description = "Step identifier", example = "extract-text")
        String id,
        
        @NotBlank
        @Schema(description = "Module to use", example = "tika-parser")
        String module,
        
        @Schema(description = "Step-specific configuration", example = "{\"maxFileSize\": \"10MB\"}")
        Map<String, Object> config,
        
        @Schema(description = "Next step(s)", example = "[\"chunk-text\"]")
        List<String> next
    ) {}
}