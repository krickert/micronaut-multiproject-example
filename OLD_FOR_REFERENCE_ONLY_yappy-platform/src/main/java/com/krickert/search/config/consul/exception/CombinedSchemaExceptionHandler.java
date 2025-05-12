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

import static com.krickert.search.config.consul.exception.ErrorBodyUtil.createErrorBody;

@Produces
@Singleton
@Requires(classes = {SchemaException.class, SchemaValidationException.class, SchemaNotFoundException.class, ExceptionHandler.class})
@Slf4j
public class CombinedSchemaExceptionHandler implements ExceptionHandler<SchemaException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, SchemaException exception) {
        String path = request.getPath();
        String message = exception.getMessage();
        // String traceId = MDC.get("traceId"); // Your ErrorBodyUtil doesn't use traceId

        if (exception instanceof SchemaValidationException) {
            log.warn("Handling SchemaValidationException for path [{}]: {}", path, message);
            Map<String, Object> errorBody = createErrorBody(HttpStatus.BAD_REQUEST, message, path);
            HttpResponse<?> response = HttpResponse.badRequest(errorBody);
            // --- ADD LOGGING ---
            log.debug("Returning response from CombinedSchemaExceptionHandler (Validation): Status={}, Body={}", response.getStatus(), errorBody);
            return response;

        } else if (exception instanceof SchemaNotFoundException) {
            log.warn("Handling SchemaNotFoundException for path [{}]: {}", path, message);
            Map<String, Object> errorBody = createErrorBody(HttpStatus.NOT_FOUND, message, path);
            HttpResponse<?> response = HttpResponse.notFound(errorBody);
             // --- ADD LOGGING ---
            log.debug("Returning response from CombinedSchemaExceptionHandler (NotFound): Status={}, Body={}", response.getStatus(), errorBody);
           return response;

        } else {
            log.error("Handling generic SchemaException for path [{}]: {}", path, message, exception);
            String genericMessage = "An internal schema processing error occurred.";
            Map<String, Object> errorBody = createErrorBody(HttpStatus.INTERNAL_SERVER_ERROR, genericMessage, path);
            HttpResponse<?> response = HttpResponse.serverError(errorBody);
             // --- ADD LOGGING ---
            log.debug("Returning response from CombinedSchemaExceptionHandler (Generic): Status={}, Body={}", response.getStatus(), errorBody);
            return response;
        }
    }
}