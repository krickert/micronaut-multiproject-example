package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.util.Map;
import java.util.Collections; // For unmodifiable map

/**
 * Configuration for a pipeline graph, which contains a map of all defined pipeline configurations.
 * This record is immutable.
 *
 * @param pipelines Map of pipeline configurations, where the key is the pipeline ID
 * (e.g., PipelineConfig.name or another unique ID). Can be null (treated as empty).
 * If provided, keys and values cannot be null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record PipelineGraphConfig(
    @JsonProperty("pipelines") Map<String, PipelineConfig> pipelines
) {
    // Canonical constructor making map unmodifiable and handling nulls
    public PipelineGraphConfig {
        pipelines = (pipelines == null) ? Collections.emptyMap() : Map.copyOf(pipelines);
        // Map.copyOf will throw NPE if map contains null keys or values.
    }
    public PipelineConfig getPipelineConfig(String pipelineId) {
        return pipelines.get(pipelineId);
    }
}
