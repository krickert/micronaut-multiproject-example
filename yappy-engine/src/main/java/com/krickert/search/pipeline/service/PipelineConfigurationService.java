package com.krickert.search.pipeline.service;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for pipeline configuration management.
 * Handles loading, saving, and watching pipeline configurations.
 */
public interface PipelineConfigurationService {
    
    /**
     * Get a pipeline configuration by ID.
     * 
     * @param pipelineId The pipeline ID
     * @return The pipeline configuration if found
     */
    Mono<PipelineConfig> getPipelineConfig(String pipelineId);
    
    /**
     * Save or update a pipeline configuration.
     * 
     * @param pipelineId The pipeline ID
     * @param config The pipeline configuration
     * @return A Mono indicating completion
     */
    Mono<Void> savePipelineConfig(String pipelineId, PipelineConfig config);
    
    /**
     * Delete a pipeline configuration.
     * 
     * @param pipelineId The pipeline ID
     * @return A Mono indicating completion
     */
    Mono<Void> deletePipelineConfig(String pipelineId);
    
    /**
     * List all pipeline configurations.
     * 
     * @return Flux of all pipeline configurations
     */
    Flux<PipelineConfig> listPipelineConfigs();
    
    /**
     * Watch for changes to a pipeline configuration.
     * 
     * @param pipelineId The pipeline ID to watch
     * @return Flux of configuration changes
     */
    Flux<PipelineConfig> watchPipelineConfig(String pipelineId);
    
    /**
     * Validate a pipeline configuration.
     * 
     * @param config The configuration to validate
     * @return Validation result
     */
    Mono<ValidationResult> validatePipelineConfig(PipelineConfig config);
    
    /**
     * Get the ordered steps for a pipeline.
     * 
     * @param pipelineId The pipeline ID
     * @return Ordered list of pipeline steps
     */
    Mono<List<PipelineStepConfig>> getOrderedSteps(String pipelineId);
    
    /**
     * Result of pipeline configuration validation.
     */
    record ValidationResult(
            boolean valid,
            List<String> errors,
            List<String> warnings
    ) {
        public static ValidationResult createValid() {
            return new ValidationResult(true, List.of(), List.of());
        }
        
        public static ValidationResult createInvalid(List<String> errors) {
            return new ValidationResult(false, errors, List.of());
        }
    }
}