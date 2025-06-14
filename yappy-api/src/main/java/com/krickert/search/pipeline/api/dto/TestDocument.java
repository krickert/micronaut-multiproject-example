package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * A generated test document.
 */
@Serdeable
@Schema(description = "Generated test document")
public record TestDocument(
    @Schema(description = "Document ID")
    String id,
    
    @Schema(description = "Document title")
    String title,
    
    @Schema(description = "Document content")
    String content,
    
    @Schema(description = "Content type", example = "text/plain")
    String contentType,
    
    @Schema(description = "Document size in bytes")
    long sizeBytes,
    
    @Schema(description = "Generation timestamp")
    Instant generatedAt,
    
    @Schema(description = "Document metadata")
    Map<String, String> metadata
) {}