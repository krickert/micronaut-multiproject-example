package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Configuration for a pipeline graph, which contains a map of pipeline configurations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineGraphConfig {
    /**
     * Map of pipeline configurations, where the key is the pipeline ID (e.g., PipelineConfig.name or another unique ID).
     */
    private Map<String, PipelineConfig> pipelines; // Renamed from pipelineGraph to pipelines for clarity
}