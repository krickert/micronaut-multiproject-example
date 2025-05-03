package com.krickert.search.config.consul.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus; // Import HttpStatus
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC; // Import MDC if you use it

import java.util.Map;

// Import static method from your actual util class
// Make sure ErrorBodyUtil.java contains:
// public static Map<String, Object> createErrorBody(HttpStatus status, String message, String path)
import static com.krickert.search.config.consul.exception.ErrorBodyUtil.createErrorBody;

/**
 * Combined Exception Handler for various Schema-related exceptions.
 * This replaces SchemaValidationExceptionHandler, SchemaNotFoundExceptionHandler, and SchemaExceptionHandler.
 */
@Produces
@Singleton
@Requires(classes = {SchemaException.class, SchemaValidationException.class, SchemaNotFoundException.class, ExceptionHandler.class})
@Slf4j
public class CombinedSchemaExceptionHandler implements ExceptionHandler<SchemaException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, SchemaException exception) {
        // String traceId = MDC.get("traceId"); // Your ErrorBodyUtil doesn't seem to use traceId, so we omit it here
        String path = request.getPath();
        String message = exception.getMessage(); // Get the exception message

        // Check for the specific exception types using instanceof
        if (exception instanceof SchemaValidationException) {
            log.warn("Handling SchemaValidationException for path [{}]: {}", path, message);
            // 1. Create the error body Map using your utility
            Map<String, Object> errorBody = createErrorBody(HttpStatus.BAD_REQUEST, message, path);
            // 2. Pass the Map as the body to the HttpResponse factory method
            return HttpResponse.badRequest(errorBody);

        } else if (exception instanceof SchemaNotFoundException) {
            log.warn("Handling SchemaNotFoundException for path [{}]: {}", path, message);
            // 1. Create the error body Map using your utility
            Map<String, Object> errorBody = createErrorBody(HttpStatus.NOT_FOUND, message, path);
            // 2. Pass the Map as the body to the HttpResponse factory method
            return HttpResponse.notFound(errorBody);

        } else {
            // Handle generic SchemaException or any other subtype
            log.error("Handling generic SchemaException for path [{}]: {}", path, message, exception);
            String genericMessage = "An internal schema processing error occurred.";
            // 1. Create the error body Map using your utility
            Map<String, Object> errorBody = createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, genericMessage, path);
            // 2. Pass the Map as the body to the HttpResponse factory method
            return HttpResponse.serverError(errorBody);
        }
    }
}