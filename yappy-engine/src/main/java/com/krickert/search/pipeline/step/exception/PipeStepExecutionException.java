// /home/krickert/IdeaProjects/github-krickert/yappy-engine/src/main/java/com/krickert/search/pipeline/step/exception/PipeStepExecutionException.java
package com.krickert.search.pipeline.step.exception;

// It's good practice to add a getter for retryable, Lombok @Getter can do this or a manual one.
// For this example, I'll assume a manual getter or Lombok @Getter is present.
public class PipeStepExecutionException extends RuntimeException {
    private final boolean retryable;

    public PipeStepExecutionException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public PipeStepExecutionException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}