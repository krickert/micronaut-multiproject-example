package com.krickert.search.config.pipeline.validation;

import com.krickert.search.config.pipeline.model.PipelineConfig;

import java.util.List;

/**
 * Interface for structural validation rules that can be applied to pipeline configurations.
 * These are client-side validators that don't require external dependencies.
 */
public interface StructuralValidationRule {
    
    /**
     * Validates a pipeline configuration.
     * 
     * @param pipelineId The ID of the pipeline
     * @param config The pipeline configuration to validate
     * @return List of validation errors (empty if valid)
     */
    List<String> validate(String pipelineId, PipelineConfig config);
    
    /**
     * Gets the name of this validation rule for reporting.
     */
    default String getRuleName() {
        return this.getClass().getSimpleName();
    }
}