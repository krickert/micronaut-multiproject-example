package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight pipeline summary for list views.
 */
@Serdeable
@Schema(description = "Summary information about a pipeline")
public record PipelineSummary(
    @Schema(description = "Unique pipeline ID", example = "document-enrichment")
    String id,
    
    @Schema(description = "Human-friendly name", example = "Document Enrichment Pipeline")
    String name,
    
    @Schema(description = "Brief description", example = "Extracts and enriches document content")
    String description,
    
    @Schema(description = "Number of processing steps")
    int stepCount,
    
    @Schema(description = "Is this pipeline active?")
    boolean active,
    
    @Schema(description = "Pipeline tags", example = "[\"production\", \"nlp\"]")
    List<String> tags,
    
    @Schema(description = "Last updated timestamp")
    Instant updatedAt
) {}