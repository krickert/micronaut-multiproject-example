package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.util.Timestamps;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.pipeline.engine.common.RouteData;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.inject.Provider;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final Provider<PipeStreamEngine> coreEngineProvider; // 👈 Use Provider
    private final DynamicConfigurationManager dynamicConfigManager;
    private final GrpcChannelManager grpcChannelManager;
    private final ServiceStatusAggregator serviceStatusAggregator;
    private final PipeStepExecutorFactory executorFactory;

    @Inject
    public PipeStreamEngineImpl(
            Provider<PipeStreamEngine> coreEngineProvider,
            DynamicConfigurationManager dynamicConfigManager,
            GrpcChannelManager grpcChannelManager,
            ServiceStatusAggregator serviceStatusAggregator,
            PipeStepExecutorFactory executorFactory
    ) {
        this.coreEngineProvider = coreEngineProvider;
        this.dynamicConfigManager = dynamicConfigManager;
        this.grpcChannelManager = grpcChannelManager;
        this.serviceStatusAggregator = serviceStatusAggregator;
        this.executorFactory = executorFactory;
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
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }

            String targetStepName = request.getTargetStepName();
            log.debug("Test processing step '{}' for streamId: {}", targetStepName, request.getStreamId());
            
            // Use the core engine to process the stream but capture the result
            // Note: For testing, we execute the step but do NOT forward to next steps
            PipeStream processedStream = executeStepWithoutForwarding(request);
            
            // Add routing information to context params for testing visibility
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(processedStream, dynamicConfigManager);
            List<RouteData> routes = stateBuilder.calculateNextRoutes();
            
            PipeStream.Builder responseBuilder = processedStream.toBuilder();
            
            // Add routing info to context params for test inspection
            if (!routes.isEmpty()) {
                responseBuilder.putContextParams("test.routing.count", String.valueOf(routes.size()));
                for (int i = 0; i < routes.size(); i++) {
                    RouteData route = routes.get(i);
                    String prefix = "test.routing." + i + ".";
                    responseBuilder.putContextParams(prefix + "destination", route.destination());
                    responseBuilder.putContextParams(prefix + "nextTargetStep", route.nextTargetStep());
                    responseBuilder.putContextParams(prefix + "transportType", route.transportType().toString());
                }
            }
            
            PipeStream response = responseBuilder.build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Failed to test pipe stream", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to test pipe: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException());
        }
    }
    
    /**
     * Executes a step without forwarding to next steps (for testing purposes).
     * This method processes the step and returns the result but does not trigger routing.
     */
    private PipeStream executeStepWithoutForwarding(PipeStream request) {
        try {
            // Get the executor for the step
            PipeStepExecutor executor = executorFactory.getExecutor(
                    request.getCurrentPipelineName(),
                    request.getTargetStepName()
            );
            
            // Execute the step
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, dynamicConfigManager);
            stateBuilder.withHopNumber((int) request.getCurrentHopNumber() + 1);
            PipeStream streamToExecute = stateBuilder.getPresentState().build();
            
            return executor.execute(streamToExecute);
        } catch (Exception e) {
            log.error("Error executing step {} for test: {}", request.getTargetStepName(), e.getMessage(), e);
            
            // Return a stream with error information
            return request.toBuilder()
                    .setStreamErrorData(ErrorData.newBuilder()
                            .setErrorMessage("Test execution failed: " + e.getMessage())
                            .setErrorCode("TEST_EXECUTION_ERROR")
                            .setOriginatingStepName(request.getTargetStepName())
                            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()))
                            .build())
                    .build();
        }
    }
}