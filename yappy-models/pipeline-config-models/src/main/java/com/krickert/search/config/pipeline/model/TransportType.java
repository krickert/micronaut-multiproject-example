package com.krickert.search.config.pipeline.model; // Or your chosen package

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Defines the transport mechanism for a pipeline step.
 */
@JsonInclude(JsonInclude.Include.NON_NULL) // Ensure nulls are not included during serialization
@Schema(description = "Transport mechanism for pipeline steps", enumAsRef = true)
public enum TransportType {
    KAFKA,
    GRPC,
    INTERNAL // For steps executed directly by the pipeline engine
}