package com.krickert.search.pipeline.step;

import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;

/**
 * Interface for executing pipeline steps.
 * This provides an abstraction over different execution strategies (gRPC, internal, etc.)
 */
public interface PipeStepExecutor {
    /**
     * Execute a pipeline step with the given PipeStream.
     * 
     * @param pipeStream The input PipeStream to process
     * @return The processed PipeStream
     * @throws PipeStepExecutionException If an error occurs during execution
     */
    PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException;

    /**
     * Get the name of the step this executor handles.
     * 
     * @return The step name
     */
    String getStepName();

    /**
     * Get the type of step this executor handles.
     * 
     * @return The step type
     */
    StepType getStepType();
}
