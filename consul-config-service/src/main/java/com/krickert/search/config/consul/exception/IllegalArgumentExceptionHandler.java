// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/exception/IllegalArgumentExceptionHandler.java
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
@Requires(classes = {IllegalArgumentException.class, ExceptionHandler.class})
public class IllegalArgumentExceptionHandler implements ExceptionHandler<IllegalArgumentException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(IllegalArgumentExceptionHandler.class);

    @Override
    public HttpResponse<?> handle(HttpRequest request, IllegalArgumentException exception) {
        LOG.warn("Handling IllegalArgumentException for path {}: {}", request.getPath(), exception.getMessage());
        return HttpResponse.badRequest(ErrorBodyUtil.createErrorBody(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getPath()));
    }
}