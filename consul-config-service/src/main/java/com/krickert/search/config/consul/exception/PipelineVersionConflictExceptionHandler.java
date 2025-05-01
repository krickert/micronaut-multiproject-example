package com.krickert.search.config.consul.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Exception handler for PipelineVersionConflictException.
 * Returns a 409 Conflict response with details about the conflict.
 */
@Singleton
@Requires(classes = {PipelineVersionConflictException.class, ExceptionHandler.class})
public class PipelineVersionConflictExceptionHandler implements ExceptionHandler<PipelineVersionConflictException, HttpResponse<?>> {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineVersionConflictExceptionHandler.class);

    /**
     * Handles the PipelineVersionConflictException by returning a 409 Conflict response with details about the conflict.
     *
     * @param request The HTTP request
     * @param exception The exception to handle
     * @return An HTTP response with status 409 Conflict and details about the conflict
     */
    @Override
    @Produces
    public HttpResponse<?> handle(HttpRequest request, PipelineVersionConflictException exception) {
        LOG.warn("Pipeline version conflict: {}", exception.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("status", HttpStatus.CONFLICT.getCode());
        body.put("error", "Pipeline Version Conflict");
        body.put("message", exception.getMessage());
        body.put("pipelineName", exception.getPipelineName());
        body.put("expectedVersion", exception.getExpectedVersion());
        body.put("actualVersion", exception.getActualVersion());
        body.put("lastUpdated", exception.getLastUpdated().toString());

        // Include delta information if available
        if (exception.getDeltaJson() != null) {
            body.put("delta", exception.getDeltaJson());
        }

        return HttpResponse.status(HttpStatus.CONFLICT).body(body);
    }
}