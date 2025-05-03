package com.krickert.search.config.consul.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Produces
@Singleton
@Requires(classes = {SchemaValidationException.class, ExceptionHandler.class})
@Slf4j
public class SchemaValidationExceptionHandler implements ExceptionHandler<SchemaValidationException, HttpResponse<Map<String, Object>>> {

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, SchemaValidationException exception) {
        log.warn("Schema validation failed during request processing: {}", exception.getMessage());
        Map<String, Object> errorBody = Map.of(
                "status", HttpStatus.BAD_REQUEST.getCode(),
                "error", "Schema Validation Failed",
                "message", exception.getMessage(),
                "validationErrors", exception.getValidationMessages() // Include detailed messages
        );
        return HttpResponse.badRequest(errorBody);
    }
}