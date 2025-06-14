package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.Collections;
import java.util.Map;

/**
 * Defines a single named pipeline, comprising a map of its constituent pipeline steps.
 * This record is immutable.
 *
 * @param name          The name of the pipeline (unique within a PipelineGraphConfig). Must not be null or blank.
 * @param pipelineSteps Map of pipeline step configurations, where the key is the step ID
 *                      (PipelineStepConfig.pipelineStepId). Can be null (treated as empty).
 *                      If provided, keys and values cannot be null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
@Serdeable
@Schema(description = "Pipeline configuration")
public record PipelineConfig(
        @JsonProperty("name") 
        @Schema(description = "Pipeline name", required = true, example = "document-processing")
        String name,
        
        @JsonProperty("pipelineSteps") 
        @Schema(description = "Map of pipeline steps by step ID")
        Map<String, PipelineStepConfig> pipelineSteps
) {
    // Canonical constructor making map unmodifiable and handling nulls
    public PipelineConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("PipelineConfig name cannot be null or blank.");
        }
        pipelineSteps = (pipelineSteps == null) ? Collections.emptyMap() : Map.copyOf(pipelineSteps);
        // Add validation for map contents if necessary (e.g., keys matching step IDs)
        // Map.copyOf will throw NPE if map contains null keys or values.
    }
}
