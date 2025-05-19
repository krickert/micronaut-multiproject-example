package com.krickert.search.config.consul.event; // Example sub-package for events

import com.fasterxml.jackson.annotation.JsonProperty;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;

import java.util.Optional;

/**
 * Event published when the active PipelineClusterConfig is successfully updated.
 *
 * @param oldConfig The previous configuration, if one existed.
 * @param newConfig The new, validated configuration.
 */
public record ClusterConfigUpdateEvent(
        @JsonProperty("oldConfig") Optional<PipelineClusterConfig> oldConfig,
        @JsonProperty("newConfig") PipelineClusterConfig newConfig
        // Optional: Map<String, String> diffSummary for more detailed changes
) {
    // Records provide a canonical constructor, getters, equals, hashCode, and toString.
    // No Lombok needed here.
    // If you add diffSummary, add it to the record components.
}