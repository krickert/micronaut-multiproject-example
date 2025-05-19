package com.krickert.search.pipeline.step.exception;

/**
 * Exception thrown when an error occurs during pipeline step execution.
 */
public class PipeStepExecutionException extends RuntimeException {
    
    /**
     * Constructs a new PipeStepExecutionException with the specified detail message.
     *
     * @param message the detail message
     */
    public PipeStepExecutionException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new PipeStepExecutionException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public PipeStepExecutionException(String message, Throwable cause) {
        super(message, cause);
    }
}