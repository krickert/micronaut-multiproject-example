package com.krickert.search.config.consul.exception;

// Helper Exception (Consider moving to exceptions package)
public class PipelineNotFoundException extends RuntimeException {
    public PipelineNotFoundException(String message) {
        super(message);
    }
}