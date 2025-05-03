package com.krickert.search.config.consul.exception;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest; // Use specific import
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import java.util.Map;

@Produces
@Singleton
@Requires(classes = {SchemaNotFoundException.class, ExceptionHandler.class, ErrorBodyUtil.class})
@Slf4j
public class SchemaNotFoundExceptionHandler implements ExceptionHandler<SchemaNotFoundException, HttpResponse<Map<String, Object>>> {

    @Override
    public HttpResponse<Map<String, Object>> handle(HttpRequest request, SchemaNotFoundException exception) { // Use HttpRequest without wildcard
        log.warn("Handling SchemaNotFoundException for path [{}]: {}", request.getPath(), exception.getMessage());

        Map<String, Object> errorBody = ErrorBodyUtil.createErrorBody(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request.getPath()
        );
        // Return the response with the standardized body and 404 status
        return HttpResponse.<Map<String, Object>>notFound().body(errorBody);
    }
}