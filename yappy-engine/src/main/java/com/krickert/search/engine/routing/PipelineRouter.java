package com.krickert.search.engine.routing;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Routes messages through a pipeline based on the pipeline configuration
 * and the current processing state.
 */
public interface PipelineRouter {
    
    /**
     * Determines the next step in the pipeline based on current state.
     * 
     * @param pipeStream The current message state
     * @param pipelineConfig The pipeline configuration
     * @return The next step to execute, or empty if pipeline is complete
     */
    Optional<PipelineStepConfig> getNextStep(PipeStream pipeStream, PipelineConfig pipelineConfig);
    
    /**
     * Checks if the pipeline execution is complete.
     * 
     * @param pipeStream The current message state
     * @param pipelineConfig The pipeline configuration
     * @return True if all steps have been executed
     */
    boolean isPipelineComplete(PipeStream pipeStream, PipelineConfig pipelineConfig);
    
    /**
     * Gets the current step being executed based on the execution history.
     * 
     * @param pipeStream The current message state
     * @return The current step name, or empty if not in a step
     */
    Optional<String> getCurrentStep(PipeStream pipeStream);
    
    /**
     * Validates that a pipeline can be executed (all required modules are available).
     * 
     * @param pipelineConfig The pipeline configuration
     * @param availableModules List of available module implementation IDs
     * @return Validation result with any errors
     */
    Mono<RouterValidationResult> validatePipeline(PipelineConfig pipelineConfig, List<String> availableModules);
    
    /**
     * Creates an execution plan for a pipeline.
     * 
     * @param pipelineConfig The pipeline configuration
     * @return The ordered list of steps to execute
     */
    List<PipelineStepConfig> createExecutionPlan(PipelineConfig pipelineConfig);
    
    /**
     * Checks if a step has already been executed.
     * 
     * @param stepName The step name to check
     * @param executionHistory The execution history from PipeStream
     * @return True if the step has been executed
     */
    boolean isStepExecuted(String stepName, List<StepExecutionRecord> executionHistory);
    
    /**
     * Result of pipeline validation.
     */
    record RouterValidationResult(
        boolean isValid,
        List<String> errors,
        List<String> warnings
    ) {
        public static RouterValidationResult valid() {
            return new RouterValidationResult(true, List.of(), List.of());
        }
        
        public static RouterValidationResult invalid(List<String> errors) {
            return new RouterValidationResult(false, errors, List.of());
        }
        
        public static RouterValidationResult withWarnings(List<String> warnings) {
            return new RouterValidationResult(true, List.of(), warnings);
        }
    }
}