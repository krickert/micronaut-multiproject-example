package com.krickert.search.config.pipeline.model; // Or your chosen package

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines the transport mechanism for a pipeline step.
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // Ensure nulls are not included during serialization
public enum TransportType {
    KAFKA,
    GRPC,
    INTERNAL // For steps executed directly by the pipeline engine
}