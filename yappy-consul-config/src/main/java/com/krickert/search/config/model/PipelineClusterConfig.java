package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

/**
 * A Pipeline Cluster is a set of services running in a network to isolate a set of pipelines.
 * This class represents the configuration for a pipeline cluster.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineClusterConfig {
    /**
     * The name of a single application cluster, which will match what is in consul.
     */
    private String clusterName;

    /**
     * The full pipeline graph configuration.
     */
    private PipelineGraphConfig pipelineGraphConfig;

    /**
     * Map of module configurations, where the key is the module implementation ID.
     * Each module represents a service implementation meant to be a type of node in the graph.
     */
    private Map<String, PipelineModuleConfiguration> moduleConfigurations;
}
