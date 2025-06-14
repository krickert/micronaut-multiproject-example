package com.krickert.search.config.pipeline.validation;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;

import java.util.List;

/**
 * Interface for structural validation rules that require the full cluster configuration.
 * These are client-side validators that validate across multiple pipelines.
 */
public interface ClusterStructuralValidationRule {
    
    /**
     * Validates a cluster configuration.
     * 
     * @param clusterConfig The cluster configuration to validate
     * @return List of validation errors (empty if valid)
     */
    List<String> validate(PipelineClusterConfig clusterConfig);
    
    /**
     * Gets the name of this validation rule for reporting.
     */
    default String getRuleName() {
        return this.getClass().getSimpleName();
    }
}