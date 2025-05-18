package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import java.util.Map;
import java.util.Collections; // For unmodifiable map

/**
 * A catalog of available pipeline module configurations.
 * Each entry maps a module's implementationId to its definition.
 * This record is immutable.
 *
 * @param availableModules Map containing the available pipeline module configurations, keyed by
 * module implementation ID. Can be null (treated as empty).
 * If provided, keys and values cannot be null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record PipelineModuleMap(
    @JsonProperty("availableModules") Map<String, PipelineModuleConfiguration> availableModules
) {
    // Canonical constructor making map unmodifiable and handling nulls
    public PipelineModuleMap {
        availableModules = (availableModules == null) ? Collections.emptyMap() : Map.copyOf(availableModules);
        // Map.copyOf will throw NPE if map contains null keys or values.
    }
}
