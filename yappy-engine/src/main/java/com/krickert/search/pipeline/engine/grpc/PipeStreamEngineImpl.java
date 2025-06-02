package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.inject.Provider;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final Provider<PipeStreamEngine> coreEngineProvider; // 👈 Use Provider
    private final DynamicConfigurationManager dynamicConfigManager;
    private final GrpcChannelManager grpcChannelManager;
    private final ServiceStatusAggregator serviceStatusAggregator;

    @Inject
    public PipeStreamEngineImpl(
            Provider<PipeStreamEngine> coreEngineProvider,
            DynamicConfigurationManager dynamicConfigManager,
            GrpcChannelManager grpcChannelManager,
            ServiceStatusAggregator serviceStatusAggregator
    ) {
        this.coreEngineProvider = coreEngineProvider;
        this.dynamicConfigManager = dynamicConfigManager;
        this.grpcChannelManager = grpcChannelManager;
        this.serviceStatusAggregator = serviceStatusAggregator;
    }

    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }

            // Always process through the core engine - it will handle module discovery
            String targetStepName = request.getTargetStepName();
            log.debug("Processing step '{}' through core engine", targetStepName);
            
            // The core engine will use module discovery to find and execute the step
            coreEngineProvider.get().processStream(request);
            
            // Check if we're using a remote module and update status accordingly
            updateServiceStatusIfRemoteModule(request.getCurrentPipelineName(), targetStepName);
            
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to process pipe stream", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to process pipe: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
    
    /**
     * Updates service status to ACTIVE_PROXYING if we're using a remote module.
     * This method checks if the module for the given step is available locally or remotely.
     */
    private void updateServiceStatusIfRemoteModule(String pipelineName, String targetStepName) {
        try {
            // Get the module implementation ID for this step
            Optional<String> moduleIdOpt = getModuleImplementationIdForStep(pipelineName, targetStepName);
            if (moduleIdOpt.isEmpty()) {
                return;
            }
            
            String moduleImplementationId = moduleIdOpt.get();
            
            // Check if the module is available locally by checking localhost ports
            boolean isLocal = grpcChannelManager.isServiceAvailableLocally(moduleImplementationId);
            
            if (!isLocal) {
                // We're using a remote module, update the status
                log.info("Using remote module '{}' for step '{}', updating status to ACTIVE_PROXYING", 
                        moduleImplementationId, targetStepName);
                serviceStatusAggregator.updateServiceStatusToProxying(moduleImplementationId);
            }
        } catch (Exception e) {
            log.warn("Failed to update service status for step '{}'", targetStepName, e);
        }
    }
    
    private Optional<String> getModuleImplementationIdForStep(String pipelineName, String stepName) {
        try {
            Optional<PipelineClusterConfig> clusterConfigOpt = dynamicConfigManager.getCurrentPipelineClusterConfig();
            if (clusterConfigOpt.isEmpty()) {
                return Optional.empty();
            }
            
            PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
            if (clusterConfig.pipelineGraphConfig() == null || 
                clusterConfig.pipelineGraphConfig().pipelines() == null) {
                return Optional.empty();
            }
            
            // Find the pipeline
            var pipeline = clusterConfig.pipelineGraphConfig().pipelines().get(pipelineName);
            if (pipeline == null || pipeline.pipelineSteps() == null) {
                return Optional.empty();
            }
            
            // Find the step
            PipelineStepConfig stepConfig = pipeline.pipelineSteps().get(stepName);
            if (stepConfig == null || stepConfig.processorInfo() == null) {
                return Optional.empty();
            }
            
            return Optional.ofNullable(stepConfig.processorInfo().grpcServiceName());
        } catch (Exception e) {
            log.error("Error getting module implementation ID for step: " + stepName, e);
            return Optional.empty();
        }
    }

    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        // If this method also uses coreEngine, it should also use coreEngineProvider.get()
        // For now, assuming it's still placeholder or has its own logic
        log.error("testPipeStream needs refactoring after core logic moved. Returning error for now.");
        responseObserver.onError(io.grpc.Status.UNIMPLEMENTED.withDescription("testPipeStream needs refactoring").asRuntimeException());
    }
}