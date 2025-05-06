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
import java.util.Collections;

/**
 * Global exceptions handler for SchemaValidationException.
 * This handler will convert SchemaValidationException to a 400 Bad Request response
 * with a JSON body containing validation error details.
 */
@Slf4j
@Singleton
@Requires(classes = {SchemaValidationException.class, ExceptionHandler.class})
public class SchemaExceptionHandler implements ExceptionHandler<SchemaValidationException, HttpResponse<?>> {

    @Override
    @Produces
    public HttpResponse<?> handle(HttpRequest request, SchemaValidationException exception) {
        log.debug("Handling SchemaValidationException: {}", exception.getMessage());
        
        // Create a response body with validation error details
        Map<String, Object> body = Map.of(
            "valid", false,
            "messages", exception.getValidationMessages()
        );
        
        // Return a 400 Bad Request response with the error details
        return HttpResponse.badRequest(body);
    }
}