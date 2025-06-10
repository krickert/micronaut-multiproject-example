package com.krickert.search.pipeline.step.exception;

/**
 * Exception thrown when an error occurs during pipeline step processing.
 */
public class PipeStepProcessingException extends RuntimeException {
    
    /**
     * Constructs a new PipeStepProcessingException with the specified detail message.
     *
     * @param message the detail message
     */
    public PipeStepProcessingException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new PipeStepProcessingException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public PipeStepProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}