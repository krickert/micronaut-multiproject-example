package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * API-friendly representation of a pipeline with metadata.
 * This is a view model that wraps the core PipelineConfig with additional metadata.
 */
@Serdeable
@Schema(description = "A data processing pipeline with full metadata")
public record PipelineView(
    @Schema(description = "Unique pipeline ID", example = "document-enrichment")
    String id,
    
    @Schema(description = "Human-friendly name", example = "Document Enrichment Pipeline")
    String name,
    
    @Schema(description = "What this pipeline does", example = "Extracts text, chunks it, and generates embeddings")
    String description,
    
    @Schema(description = "Processing steps in a simplified format")
    List<PipelineStepView> steps,
    
    @Schema(description = "Optional tags for organization", example = "[\"nlp\", \"production\"]")
    List<String> tags,
    
    @Schema(description = "Is this pipeline active?")
    boolean active,
    
    @Schema(description = "When this pipeline was created")
    Instant createdAt,
    
    @Schema(description = "When this pipeline was last modified")
    Instant updatedAt,
    
    @Schema(description = "Which cluster this pipeline belongs to")
    String cluster
) {
    
    /**
     * Simplified view of a pipeline step for API consumers.
     */
    @Serdeable
    @Schema(description = "A processing step in the pipeline")
    public record PipelineStepView(
        @Schema(description = "Step identifier", example = "extract-text")
        String id,
        
        @Schema(description = "Module that handles this step", example = "tika-parser")
        String module,
        
        @Schema(description = "Step configuration", example = "{\"maxFileSize\": \"10MB\"}")
        Map<String, Object> config,
        
        @Schema(description = "Next steps (for branching pipelines)", example = "[\"chunk-text\"]")
        List<String> next,
        
        @Schema(description = "Transport type", allowableValues = {"direct", "kafka"}, example = "direct")
        String transport,
        
        @Schema(description = "Kafka topic if transport is kafka", example = "parsed-documents")
        String kafkaTopic
    ) {}
}