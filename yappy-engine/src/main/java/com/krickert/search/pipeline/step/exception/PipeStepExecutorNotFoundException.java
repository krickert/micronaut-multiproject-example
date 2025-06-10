package com.krickert.search.pipeline.step.exception;

/**
 * Exception thrown when a pipeline step executor cannot be found.
 */
public class PipeStepExecutorNotFoundException extends RuntimeException {
    
    /**
     * Constructs a new PipeStepExecutorNotFoundException with the specified detail message.
     *
     * @param message the detail message
     */
    public PipeStepExecutorNotFoundException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new PipeStepExecutorNotFoundException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public PipeStepExecutorNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}