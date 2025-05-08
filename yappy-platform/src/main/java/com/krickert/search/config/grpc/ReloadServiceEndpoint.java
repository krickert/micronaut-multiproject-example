package com.krickert.search.config.grpc;

import com.krickert.search.config.consul.service.ConfigurationService;
import com.krickert.search.model.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class implements the gRPC service for handling reload operations for pipelines, services,
 * and applications. It extends the ReloadServiceGrpc.ReloadServiceImplBase and provides concrete
 * implementations for the defined RPC methods.
 *<br/>
 * The service aims to facilitate cache invalidation and reload operations based on client requests.
 * It uses the ConfigurationService to perform the respective cache invalidation tasks.
 *<br/>
 * The class is marked as a Singleton and acts as a gRPC service endpoint. It logs the incoming
 * requests and handles operations such as:
 *<br/>
 * - Reloading configuration for specific pipelines
 * - Reloading configuration for specific services
 * - Handling application-specific configuration changes
 *<br/>
 * Detailed logging is provided for both successful operations and error scenarios.
 * Validation is performed to ensure mandatory input data is present before proceeding with
 * any operation.
 */
@Singleton
@GrpcService
public class ReloadServiceEndpoint extends ReloadServiceGrpc.ReloadServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ReloadServiceEndpoint.class);
    private final ConfigurationService configurationService;
    // TODO: Inject other services if needed for service/app config invalidation

    @Inject
    public ReloadServiceEndpoint(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    @Override
    public void reloadPipeline(PipelineReloadRequest request, StreamObserver<PipelineReloadResponse> responseObserver) {
        log.info("gRPC endpoint received reloadPipeline request for: {}", request.getPipelineName());
        PipelineReloadResponse.Builder responseBuilder = PipelineReloadResponse.newBuilder();
        try {
            if (request.getPipelineName() == null || request.getPipelineName().isBlank()) {
                 throw new IllegalArgumentException("pipeline_name cannot be empty");
            }
            // Call the cache invalidation method from Step 1
            configurationService.invalidatePipelineConfig(request.getPipelineName());
            responseBuilder.setSuccess(true).setMessage("Pipeline cache invalidated for " + request.getPipelineName());
            log.debug("Successfully invalidated pipeline cache for: {}", request.getPipelineName());
        } catch (Exception e) {
            log.error("Error invalidating pipeline cache for: {}", request.getPipelineName(), e);
            responseBuilder.setSuccess(false).setMessage("Error: " + e.getMessage());
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void reloadService(PipeStepReloadRequest request, StreamObserver<PipeStepReloadResponse> responseObserver) {
        log.info("gRPC endpoint received reloadService request for: {}", request.getServiceName());
        PipeStepReloadResponse.Builder responseBuilder = PipeStepReloadResponse.newBuilder();
        try {
            if (request.getServiceName() == null || request.getServiceName().isBlank()) {
                throw new IllegalArgumentException("service_name cannot be empty");
            }
            // Call the cache invalidation method
            configurationService.invalidateServiceConfig(request.getServiceName());
            responseBuilder.setSuccess(true).setMessage("Service cache invalidated for " + request.getServiceName());
            log.debug("Successfully invalidated service cache for: {}", request.getServiceName());
        } catch (Exception e) {
            log.error("Error invalidating service cache for: {}", request.getServiceName(), e);
            responseBuilder.setSuccess(false).setMessage("Error: " + e.getMessage());
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void applicationChanged(ApplicationChangeEvent request, StreamObserver<ApplicationChangeResponse> responseObserver) {
        log.info("gRPC endpoint received applicationChanged request for: {}", request.getApplication());
        ApplicationChangeResponse.Builder responseBuilder = ApplicationChangeResponse.newBuilder();
        try {
            if (request.getApplication() == null || request.getApplication().isBlank()) {
                throw new IllegalArgumentException("application name cannot be empty");
            }
            // Call the cache invalidation method
            configurationService.invalidateApplicationConfig(request.getApplication());
            responseBuilder.setSuccess(true).setMessage("Application cache invalidated for " + request.getApplication());
            log.debug("Successfully invalidated application cache for: {}", request.getApplication());
        } catch (Exception e) {
            log.error("Error invalidating application cache for: {}", request.getApplication(), e);
            responseBuilder.setSuccess(false).setMessage("Error: " + e.getMessage());
        }
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
