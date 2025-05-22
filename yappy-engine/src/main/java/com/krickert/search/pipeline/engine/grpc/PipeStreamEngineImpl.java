package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.engine.common.RouteData;
import com.krickert.search.pipeline.engine.exception.PipelineConfigurationException;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of the PipeStreamEngine gRPC service.
 */
@Singleton
@GrpcService
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase {
    private static final Logger log = LoggerFactory.getLogger(PipeStreamEngineImpl.class);

    private final PipeStepExecutorFactory executorFactory;
    private final PipeStreamGrpcForwarder grpcForwarder;
    private final KafkaForwarder kafkaForwarder;
    private final DynamicConfigurationManager configManager;

    // Define keys for context params in testPipeStream as constants
    private static final String TEST_ROUTE_PREFIX = "route_";
    private static final String TEST_ROUTE_TARGET_PIPELINE_SUFFIX = "_target_pipeline";
    private static final String TEST_ROUTE_NEXT_STEP_SUFFIX = "_next_step";
    private static final String TEST_ROUTE_DESTINATION_SUFFIX = "_destination";
    private static final String TEST_ROUTE_TRANSPORT_TYPE_SUFFIX = "_transport_type";

    /**
     * Private static record to hold the pipeline name and step name for a connector's entry point.
     * This is used internally by PipeStreamEngineImpl and is not part of the serialized config.
     */
    private record ConnectorEntryPoint(String pipelineName, String stepName) {}


    @Inject
    public PipeStreamEngineImpl(PipeStepExecutorFactory executorFactory,
                                PipeStreamGrpcForwarder grpcForwarder,
                                KafkaForwarder kafkaForwarder,
                                DynamicConfigurationManager configManager) {
        this.executorFactory = executorFactory;
        this.grpcForwarder = grpcForwarder;
        this.kafkaForwarder = kafkaForwarder;
        this.configManager = configManager;
    }

    /**
     * Tests the pipe stream execution by processing a given request, applying transformations,
     * and calculating routes, then returning the processed data to the response observer.
     *
     * @param request the incoming PipeStream message containing information required to process
     *                the current pipeline and target step
     * @param responseObserver the observer to send processed PipeStream data or errors back
     */
    @Override
    public void testPipeStream(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }

            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(request, configManager);
            stateBuilder.withHopNumber((int) request.getCurrentHopNumber() + 1);

            PipeStepExecutor executor = executorFactory.getExecutor(
                    request.getCurrentPipelineName(),
                    request.getTargetStepName());

            PipeStream processedStream = executor.execute(stateBuilder.getPresentState().build());
            stateBuilder = new PipeStreamStateBuilderImpl(processedStream, configManager);

            List<RouteData> routes = stateBuilder.calculateNextRoutes();
            PipeStream response = stateBuilder.build();

            PipeStream.Builder responseWithRoutes = response.toBuilder();
            for (int i = 0; i < routes.size(); i++) {
                RouteData route = routes.get(i);
                String routePrefix = TEST_ROUTE_PREFIX + i;
                responseWithRoutes.putContextParams(routePrefix + TEST_ROUTE_TARGET_PIPELINE_SUFFIX, route.targetPipeline());
                responseWithRoutes.putContextParams(routePrefix + TEST_ROUTE_NEXT_STEP_SUFFIX, route.nextTargetStep());
                responseWithRoutes.putContextParams(routePrefix + TEST_ROUTE_DESTINATION_SUFFIX, route.destination());
                responseWithRoutes.putContextParams(routePrefix + TEST_ROUTE_TRANSPORT_TYPE_SUFFIX, route.transportType().toString());
            }

            responseObserver.onNext(responseWithRoutes.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in testPipeStream for streamId {}: {}", request.getStreamId(), e.getMessage(), e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void processPipeAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
        try {
            if (request.getTargetStepName() == null || request.getTargetStepName().isEmpty()) {
                log.error("processPipeAsync called with invalid request: targetStepName missing. StreamId: {}", request.getStreamId());
                responseObserver.onError(new IllegalArgumentException("Target step name must be set in the request"));
                return;
            }
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();

            executeStepAndForward(request);
        } catch (Exception e) {
            log.error("Initial error in processPipeAsync for streamId {}: {}", request.getStreamId(), e.getMessage(), e);
        }
    }

    /**
     * Processes a connector document by identifying the source connector step,
     * constructing a structured PipeStream, and initiating a pipeline ingestion process.
     * Responds with either success or error details.
     *
     * @param request the {@link ConnectorRequest} containing the document, source identifier,
     *                suggested stream ID, and initial context parameters
     * @param responseObserver the {@link StreamObserver} for sending back a {@link ConnectorResponse}
     *                         indicating the processing outcome and stream details
     */
    @Override
    public void processConnectorDoc(ConnectorRequest request, StreamObserver<ConnectorResponse> responseObserver) {
        String streamId = null;
        String pipelineNameForLog = null;
        String connectorStepNameForLog = null;

        try {
            if (request.getSourceIdentifier() == null || request.getSourceIdentifier().isBlank()) {
                throw new IllegalArgumentException("Source identifier must be provided in ConnectorRequest.");
            }

            // The sourceIdentifier IS the name of the PipeStepConfig representing the connector.
            ConnectorEntryPoint connectorStepDetails = findConnectorStepDetailsByIdentifier(request.getSourceIdentifier());
            pipelineNameForLog = connectorStepDetails.pipelineName();
            connectorStepNameForLog = connectorStepDetails.stepName(); // This will be == request.getSourceIdentifier()

            streamId = (request.getSuggestedStreamId() == null || request.getSuggestedStreamId().isEmpty() || request.getSuggestedStreamId().isBlank())
                    ? UUID.randomUUID().toString()
                    : request.getSuggestedStreamId();

            PipeStream.Builder pipeStreamBuilder = PipeStream.newBuilder()
                    .setStreamId(streamId)
                    .setDocument(request.getDocument())
                    .setCurrentPipelineName(connectorStepDetails.pipelineName())
                    .setCurrentHopNumber(0)
                    .putAllContextParams(request.getInitialContextParamsMap())
                    .setTargetStepName(connectorStepDetails.stepName()); // Target the connector's own step config

            ConnectorResponse response = ConnectorResponse.newBuilder()
                    .setStreamId(streamId)
                    .setAccepted(true)
                    .setMessage("Ingestion accepted for stream ID " + streamId +
                            ", targeting connector step: " + connectorStepDetails.stepName() +
                            " in pipeline: " + connectorStepDetails.pipelineName())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();

            executeStepAndForward(pipeStreamBuilder.build());

        } catch (Exception e) {
            log.error("Error processing connector document. SourceId: {}, ConnectorStep: {}.{}. StreamId: {}. Error: {}",
                    request.getSourceIdentifier(), pipelineNameForLog, connectorStepNameForLog, streamId, e.getMessage(), e);
            ConnectorResponse.Builder errorResponseBuilder = ConnectorResponse.newBuilder()
                    .setAccepted(false)
                    .setMessage("Error processing connector document: " + e.getMessage());
            if (streamId != null) {
                errorResponseBuilder.setStreamId(streamId);
            }
            responseObserver.onNext(errorResponseBuilder.build());
            responseObserver.onCompleted();
        }
    }


    private void executeStepAndForward(PipeStream incomingPipeStream) {
        String streamId = incomingPipeStream.getStreamId();
        String originalTargetStepName = incomingPipeStream.getTargetStepName();
        PipeStream streamAfterExecution = null; // To hold the result of the current step's execution

        try {
            log.debug("Executing step: {} for streamId: {}", originalTargetStepName, streamId);
            PipeStreamStateBuilder stateBuilder = new PipeStreamStateBuilderImpl(incomingPipeStream, configManager);
            stateBuilder.withHopNumber((int) incomingPipeStream.getCurrentHopNumber() + 1);

            PipeStepExecutor executor = executorFactory.getExecutor(
                    incomingPipeStream.getCurrentPipelineName(),
                    originalTargetStepName);

            streamAfterExecution = executor.execute(stateBuilder.getPresentState().build());

            PipeStreamStateBuilder finalStateBuilder = new PipeStreamStateBuilderImpl(streamAfterExecution, configManager);
            List<RouteData> routes = finalStateBuilder.calculateNextRoutes();
            PipeStream streamStateForForwarding = finalStateBuilder.build(); // Final state after current step execution

            if (routes.isEmpty()) {
                log.info("No further routes to forward for streamId: {} from executed step {}. Pipeline processing for this branch may be complete.",
                        streamId, originalTargetStepName);
                return;
            }

            List<CompletableFuture<?>> dispatchFutures = new ArrayList<>();

            for (RouteData route : routes) {
                log.debug("Preparing to route streamId: {} from executed step {} to destination: {}, nextActualStep: {}, transport: {}",
                        streamId, originalTargetStepName, route.destination(), route.nextTargetStep(), route.transportType());

                PipeStream.Builder destinationPipeBuilder = streamStateForForwarding.toBuilder()
                        .setTargetStepName(route.nextTargetStep()) // Target for the *next* step
                        .setCurrentPipelineName(route.targetPipeline()); // Ensure correct pipeline for next step

                PipeStream pipeToDispatch = destinationPipeBuilder.build();

                if (route.transportType() == TransportType.KAFKA) {
                    CompletableFuture<RecordMetadata> kafkaFuture = kafkaForwarder.forwardToKafka(pipeToDispatch, route.destination());
                    dispatchFutures.add(kafkaFuture.whenComplete((metadata, ex) -> {
                        if (ex != null) {
                            log.error("ASYNC FAILURE: Failed to forward streamId {} to Kafka topic {} for next step {}: {}",
                                    streamId, route.destination(), route.nextTargetStep(), ex.getMessage());
                            handleFailedDispatch(pipeToDispatch, route, ex, "KAFKA_FORWARD_FAILURE");
                        } else {
                            log.info("ASYNC SUCCESS: Forwarded streamId {} to Kafka topic {} (Partition: {}, Offset: {}) for next step {}",
                                    streamId, route.destination(), metadata.partition(), metadata.offset(), route.nextTargetStep());
                        }
                    }));
                } else if (route.transportType() == TransportType.GRPC) {
                    PipeStreamGrpcForwarder.RouteData grpcRouteData = PipeStreamGrpcForwarder.RouteData.builder()
                            .targetPipeline(route.targetPipeline())
                            .nextTargetStep(route.nextTargetStep())
                            .destination(route.destination()) // gRPC service name
                            .streamId(streamId)
                            .build();
                    // Pass the already built pipeToDispatch to the gRPC forwarder
                    CompletableFuture<Void> grpcFuture = grpcForwarder.forwardToGrpc(pipeToDispatch.toBuilder(), grpcRouteData); // Pass builder if forwarder modifies it, or built PipeStream
                    dispatchFutures.add(grpcFuture.whenComplete((v, ex) -> {
                        if (ex != null) {
                            log.error("ASYNC FAILURE: Failed to forward streamId {} via gRPC to service {} for next step {}: {}",
                                    streamId, route.destination(), route.nextTargetStep(), ex.getMessage());
                            handleFailedDispatch(pipeToDispatch, route, ex, "GRPC_FORWARD_FAILURE");
                        } else {
                            log.info("ASYNC SUCCESS: Forwarded streamId {} via gRPC to service {} for next step {}",
                                    streamId, route.destination(), route.nextTargetStep());
                        }
                    }));
                } else {
                    log.warn("Unsupported transport type {} for route from step {} in streamId: {}. Skipping.",
                            route.transportType(), originalTargetStepName, streamId);
                }
            }

            CompletableFuture.allOf(dispatchFutures.toArray(new CompletableFuture[0]))
                    .whenComplete((v, ex) -> {
                        if (ex != null) {
                            log.warn("At least one asynchronous dispatch operation failed for streamId {} from step {}. Individual errors logged above.", streamId, originalTargetStepName);
                        } else {
                            log.info("All asynchronous dispatch operations initiated for streamId {} from step {} (individual success/failure logged per route).", streamId, originalTargetStepName);
                        }
                    });
            log.info("Successfully processed step {} and initiated forwarding for {} route(s) for streamId: {}",
                    originalTargetStepName, routes.size(), streamId);

        } catch (Exception e) { // Catches synchronous errors from executor.execute() or route calculation
            log.error("SYNC FAILURE: Critical error during execution of step {} or route calculation for streamId {}: {}. This occurred after initial gRPC response.",
                    originalTargetStepName, streamId, e.getMessage(), e);
            PipeStream streamForErrorHandling = (streamAfterExecution != null) ? streamAfterExecution : incomingPipeStream;
            handleFailedStepExecutionOrRouting(streamForErrorHandling, originalTargetStepName, e, "STEP_EXECUTION_OR_ROUTING_FAILURE");
        }
    }

    // New private helper method in PipeStreamEngineImpl
    private void handleFailedDispatch(PipeStream dispatchedStream, RouteData failedRoute, Throwable dispatchException, String errorCode) {
        String streamId = dispatchedStream.getStreamId();
        log.error("Handling failed dispatch for streamId: {}. Route Destination: {}, Next Step: {}, ErrorCode: {}, Exception: {}",
                streamId, failedRoute.destination(), failedRoute.nextTargetStep(), errorCode, dispatchException.getMessage());

        PipeStream.Builder errorPipeBuilder = dispatchedStream.toBuilder();

        com.google.protobuf.Timestamp errorTimestamp = com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis());

        ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                .setErrorMessage("Failed to dispatch to " + failedRoute.destination() + " for step " + failedRoute.nextTargetStep() + ": " + dispatchException.getMessage())
                .setErrorCode(errorCode)
                .setOriginatingStepName(dispatchedStream.getTargetStepName()) // The step that *produced* the routes
                .setAttemptedTargetStepName(failedRoute.nextTargetStep())    // The step we *tried* to dispatch to
                .setTechnicalDetails(dispatchException.toString())
                .setTimestamp(errorTimestamp);

        // Add a specific history record for this dispatch failure
        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(dispatchedStream.getCurrentHopNumber()) // Hop of the step that produced this route
                .setStepName(dispatchedStream.getTargetStepName())    // Step that produced this route
                .setAttemptedTargetStepName(failedRoute.nextTargetStep()) // The step we tried to dispatch to
                .setStatus("DISPATCH_FAILURE")
                .setStartTime(errorTimestamp) // Approximate time of failure detection
                .setEndTime(errorTimestamp)
                .setErrorInfo(errorDataBuilder.build());

        errorPipeBuilder.addHistory(historyBuilder.build());
        // Consider if the main stream_error_data should be set.
        // If any dispatch fails, perhaps the stream for that *branch* is considered failed.
        // errorPipeBuilder.setStreamErrorData(errorDataBuilder.build()); // This might be too global.

        PipeStream streamForDlq = errorPipeBuilder.build();
        String errorRoutingTopic = "pipeline.errors." + dispatchedStream.getCurrentPipelineName(); // Example error topic
        log.warn("Routing streamId {} (after dispatch failure to {}) to error topic {}", streamId, failedRoute.destination(), errorRoutingTopic);
        kafkaForwarder.forwardToErrorTopic(streamForDlq, errorRoutingTopic); // Assuming originalTopic is for context
    }

    // New private helper method in PipeStreamEngineImpl
    private void handleFailedStepExecutionOrRouting(PipeStream streamStateAtFailure, String failedStepName, Throwable executionException, String errorCode) {
        String streamId = streamStateAtFailure.getStreamId();
        log.error("Handling failed step execution or routing calculation for streamId: {}. Failed Step: {}, ErrorCode: {}, Exception: {}",
                streamId, failedStepName, errorCode, executionException.getMessage());

        PipeStream.Builder errorPipeBuilder = streamStateAtFailure.toBuilder();
        com.google.protobuf.Timestamp errorTimestamp = com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis());

        ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                .setErrorMessage("Step execution or routing calculation failed for " + failedStepName + ": " + executionException.getMessage())
                .setErrorCode(errorCode)
                .setOriginatingStepName(failedStepName)
                .setTechnicalDetails(executionException.toString())
                .setTimestamp(errorTimestamp);

        // Mark the entire stream as failed
        errorPipeBuilder.setStreamErrorData(errorDataBuilder.build());
        errorPipeBuilder.clearTargetStepName(); // Stop further processing for this path

        // Update the last history record or add a new one.
        // If executor.execute() added its own failure record, this might be supplemental.
        // For simplicity, we add a new orchestrator-level failure record.
        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(streamStateAtFailure.getCurrentHopNumber())
                .setStepName(failedStepName)
                .setStatus("ORCHESTRATION_ERROR_HALT")
                .setStartTime(errorTimestamp) // Approximate time
                .setEndTime(errorTimestamp)
                .setErrorInfo(errorDataBuilder.build());
        errorPipeBuilder.addHistory(historyBuilder.build());

        PipeStream streamForDlq = errorPipeBuilder.build();
        String errorRoutingTopic = "pipeline.errors." + streamStateAtFailure.getCurrentPipelineName();
        log.warn("Routing streamId {} to error topic {} due to synchronous step/routing failure.", streamId, errorRoutingTopic);
        kafkaForwarder.forwardToErrorTopic(streamForDlq, errorRoutingTopic);
    }

    /**
     * Finds the pipeline and step details for a connector, where the sourceIdentifier
     * is the name of the PipeStepConfig representing the connector.
     *
     * @param sourceIdentifier The name of the PipeStepConfig that is the connector.
     * @return ConnectorEntryPoint containing the pipelineName and stepName.
     * @throws PipelineConfigurationException if the connector step cannot be found.
     */
    private ConnectorEntryPoint findConnectorStepDetailsByIdentifier(String sourceIdentifier) throws PipelineConfigurationException {
        Optional<PipelineClusterConfig> clusterConfigOpt = configManager.getCurrentPipelineClusterConfig();
        if (clusterConfigOpt.isEmpty()) {
            throw new PipelineConfigurationException("No cluster configuration found. Cannot find connector step: " + sourceIdentifier);
        }
        PipelineClusterConfig clusterConfig = clusterConfigOpt.get();

        // Iterate through all pipelines and their steps to find the one matching the sourceIdentifier
        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipelineConfig = pipelineEntry.getValue();
            if (pipelineConfig.pipelineSteps().containsKey(sourceIdentifier)) {
                // Found the step. The sourceIdentifier is the stepName.
                log.info("Connector sourceIdentifier '{}' found as PipeStepConfig: Pipeline='{}', Step='{}'",
                        sourceIdentifier, pipelineName, sourceIdentifier);
                // The ReferentialIntegrityValidator should ensure that a step used as a connector
                // (i.e., its name is used as a sourceIdentifier) has the appropriate characteristics
                // (e.g., no inputs, has outputs, potentially a specific StepType if you add one).
                return new ConnectorEntryPoint(pipelineName, sourceIdentifier);
            }
        }

        throw new PipelineConfigurationException("No PipeStepConfig found with name matching source identifier: " + sourceIdentifier +
                ". Ensure a PipeStepConfig named '" + sourceIdentifier + "' exists and is intended for use as a connector.");
    }
}