// File: micronaut-multiproject-example-new/consul-config-service/src/main/java/com/krickert/search/config/consul/exceptions/PipelineVersionConflictExceptionHandler.java
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

// ErrorBodyUtil assumed to be created separately or keep helpers static in controller
// import static com.krickert.search.config.consul.api.PipelineController.createConflictErrorBody;

@Produces
@Singleton
@Requires(classes = {PipelineVersionConflictException.class, ExceptionHandler.class})
public class PipelineVersionConflictExceptionHandler implements ExceptionHandler<PipelineVersionConflictException, HttpResponse<?>> {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineVersionConflictExceptionHandler.class);

    @Override
    public HttpResponse<?> handle(HttpRequest request, PipelineVersionConflictException exception) {
        LOG.warn("Handling PipelineVersionConflictException for path {}: {}", request.getPath(), exception.getMessage());
        return HttpResponse.status(HttpStatus.CONFLICT)
                           .body(ErrorBodyUtil.createConflictErrorBody(exception, request.getPath()));
    }
}