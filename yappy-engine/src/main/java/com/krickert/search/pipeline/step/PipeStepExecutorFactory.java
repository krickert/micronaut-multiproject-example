package com.krickert.search.pipeline.step;

import com.krickert.search.pipeline.step.exception.PipeStepExecutorNotFoundException;

/**
 * Factory for creating PipeStepExecutor instances.
 */
public interface PipeStepExecutorFactory {
    /**
     * Get an executor for the specified pipeline and step.
     * 
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The appropriate executor for the step
     * @throws PipeStepExecutorNotFoundException If no executor can be found for the step
     */
    PipeStepExecutor getExecutor(String pipelineName, String stepName) throws PipeStepExecutorNotFoundException;
}