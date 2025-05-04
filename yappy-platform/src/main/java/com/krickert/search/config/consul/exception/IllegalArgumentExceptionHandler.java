package com.krickert.search.config.consul.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
// Removed HttpStatus import as it's not directly used here
import io.micronaut.http.annotation.Produces; // Keep Produces import
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
// Assuming ErrorBodyUtil is NOT used by this specific handler based on previous code shown
// If it IS used, adjust accordingly.

/**
 * Global exception handler for IllegalArgumentException.
 * This handler will convert IllegalArgumentException to a 400 Bad Request response
 * with a JSON body containing the error message.
 */
@Produces // Moved annotation to class level
@Slf4j
@Singleton
@Requires(classes = {IllegalArgumentException.class, ExceptionHandler.class})
public class IllegalArgumentExceptionHandler implements ExceptionHandler<IllegalArgumentException, HttpResponse<?>> {

    @Override
    // Removed @Produces from method level
    public HttpResponse<?> handle(HttpRequest request, IllegalArgumentException exception) {
        String exceptionMessage = exception.getMessage(); // Store message
        log.debug("Handling IllegalArgumentException: \"{}\" for path: {}", exceptionMessage, request.getPath()); // Log full message

        // Create a response body with JUST the error message (as per your previous code)
        Map<String, Object> body = Map.of(
                "message", exceptionMessage
        );

        // --- ADD THIS LOG ---
        log.debug("Constructed error body for IllegalArgumentException: {}", body);

        // Return a 400 Bad Request response with the error details
        return HttpResponse.badRequest(body);
    }
}