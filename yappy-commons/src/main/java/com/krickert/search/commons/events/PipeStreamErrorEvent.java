package com.krickert.search.commons.events;

import com.krickert.search.model.PipeStream;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

import java.time.Instant;
import java.util.Map;

/**
 * Event published when PipeStream processing fails.
 */
@Introspected
public class PipeStreamErrorEvent extends BaseEvent {
    
    private final PipeStream pipeStream;
    private final String failedModule;
    private final String errorMessage;
    private final String errorType;
    private final Throwable cause;
    private final Instant failureTime;
    private final boolean retryable;
    private final int attemptNumber;
    private final Map<String, Object> errorContext;
    
    public PipeStreamErrorEvent(
            @NonNull PipeStream pipeStream,
            @NonNull String failedModule,
            @NonNull String errorMessage,
            @NonNull String errorType,
            @Nullable Throwable cause,
            boolean retryable,
            int attemptNumber,
            @NonNull EventMetadata metadata,
            @NonNull Map<String, Object> errorContext) {
        super(metadata);
        this.pipeStream = pipeStream;
        this.failedModule = failedModule;
        this.errorMessage = errorMessage;
        this.errorType = errorType;
        this.cause = cause;
        this.failureTime = Instant.now();
        this.retryable = retryable;
        this.attemptNumber = attemptNumber;
        this.errorContext = errorContext;
    }
    
    /**
     * Factory method for creating error event.
     */
    public static PipeStreamErrorEvent create(
            PipeStream pipeStream,
            String failedModule,
            Throwable error,
            boolean retryable,
            int attemptNumber,
            EventMetadata metadata) {
        
        Map<String, Object> context = Map.of(
            "exception.class", error.getClass().getName(),
            "exception.message", error.getMessage() != null ? error.getMessage() : "",
            "stack.trace.first", getFirstStackTraceElement(error)
        );
        
        return new PipeStreamErrorEvent(
            pipeStream,
            failedModule,
            error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName(),
            error.getClass().getSimpleName(),
            error,
            retryable,
            attemptNumber,
            metadata,
            context
        );
    }
    
    private static String getFirstStackTraceElement(Throwable error) {
        if (error.getStackTrace() != null && error.getStackTrace().length > 0) {
            return error.getStackTrace()[0].toString();
        }
        return "No stack trace available";
    }
    
    // Getters
    public PipeStream getPipeStream() {
        return pipeStream;
    }
    
    public String getFailedModule() {
        return failedModule;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public String getErrorType() {
        return errorType;
    }
    
    @Nullable
    public Throwable getCause() {
        return cause;
    }
    
    public Instant getFailureTime() {
        return failureTime;
    }
    
    public boolean isRetryable() {
        return retryable;
    }
    
    public int getAttemptNumber() {
        return attemptNumber;
    }
    
    public Map<String, Object> getErrorContext() {
        return errorContext;
    }
}