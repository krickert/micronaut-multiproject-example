// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/exception/PipelineNotFoundExceptionHandler.java
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

@Produces
@Singleton
@Requires(classes = {PipelineNotFoundException.class, ExceptionHandler.class})
public class PipelineNotFoundExceptionHandler implements ExceptionHandler<PipelineNotFoundException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineNotFoundExceptionHandler.class);

    @Override
    public HttpResponse<?> handle(HttpRequest request, PipelineNotFoundException exception) {
        LOG.warn("Handling PipelineNotFoundException for path {}: {}", request.getPath(), exception.getMessage());
        return HttpResponse.notFound(ErrorBodyUtil.createErrorBody(HttpStatus.NOT_FOUND, exception.getMessage(), request.getPath()));
    }
}