package com.krickert.yappy.engine.controller;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.exceptions.SerdeException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global exception handler for the Yappy Engine application.
 * Handles validation errors, serialization errors, and other common exceptions.
 */
@Requires(classes = {ConstraintViolationException.class, SerdeException.class})
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Error(global = true)
    public HttpResponse<ErrorResponse> constraintViolationHandler(HttpRequest<?> request, 
                                                                 ConstraintViolationException ex) {
        LOG.warn("Validation error on {}: {}", request.getPath(), ex.getMessage());
        
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        String message = violations.stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        
        return HttpResponse.badRequest(new ErrorResponse("Validation failed", message));
    }

    @Error(global = true)
    public HttpResponse<ErrorResponse> serdeExceptionHandler(HttpRequest<?> request, SerdeException ex) {
        LOG.warn("Serialization error on {}: {}", request.getPath(), ex.getMessage());
        return HttpResponse.badRequest(new ErrorResponse("Serialization error", ex.getMessage()));
    }

    @Error(global = true)
    public HttpResponse<ErrorResponse> httpStatusExceptionHandler(HttpRequest<?> request, 
                                                                 HttpStatusException ex) {
        LOG.warn("HTTP error on {}: {} - {}", request.getPath(), ex.getStatus(), ex.getMessage());
        return HttpResponse.status(ex.getStatus()).body(new ErrorResponse("Request failed", ex.getMessage()));
    }

    @Error(global = true, status = HttpStatus.INTERNAL_SERVER_ERROR)
    public HttpResponse<ErrorResponse> defaultExceptionHandler(HttpRequest<?> request, Throwable ex) {
        LOG.error("Unexpected error on {}: {}", request.getPath(), ex.getMessage(), ex);
        return HttpResponse.serverError(new ErrorResponse("Internal server error", "An unexpected error occurred"));
    }

    @io.micronaut.serde.annotation.Serdeable
    public static class ErrorResponse {
        private final String error;
        private final String message;

        public ErrorResponse(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public String getError() { return error; }
        public String getMessage() { return message; }
    }
}