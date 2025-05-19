package com.krickert.search.engine.exception;

import lombok.Getter;

// Ensure StepExecutionException is defined (as previously provided)
@Getter
public class StepExecutionException extends RuntimeException {
    private final boolean retryable;

    public StepExecutionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public StepExecutionException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }
}