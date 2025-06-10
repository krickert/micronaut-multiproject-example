package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
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
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of the PipeStreamEngine gRPC service.
 */
@Singleton
@GrpcService
@Requires(property = "consul.enabled", value = "true", defaultValue = "true")
public class PipeStreamEngineImpl extends PipeStreamEngineGrpc.PipeStreamEngineImplBase implements com.krickert.search.pipeline.engine.PipeStreamEngine {
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
    private record ConnectorEntryPoint(String pipelineName, String stepName) {
    }


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
     * @param request          the incoming PipeStream message containing information required to process
     *                         the current pipeline and target step
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


    // New private helper method in PipeStreamEngineImpl
    void handleFailedDispatch(PipeStream dispatchedStream, RouteData failedRoute, Throwable dispatchException, String errorCode) {
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
    void handleFailedStepExecutionOrRouting(PipeStream streamStateAtFailure, String failedStepName, Throwable executionException, String errorCode) {
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


    private void executeStepAndForward(PipeStream incomingPipeStream) {
        String streamId = incomingPipeStream.getStreamId();
        String originalTargetStepName = incomingPipeStream.getTargetStepName();
        PipeStream streamForNextProcessing = incomingPipeStream;
        boolean stepExecutedSuccessfully = false;
        PipeStepExecutionException lastSeenExceptionFromExecutor = null;

        PipelineStepConfig stepConfig;
        try { // Main try block for the entire method's core logic
            try {
                Optional<PipelineConfig> pipelineConfigOpt = configManager.getPipelineConfig(incomingPipeStream.getCurrentPipelineName());
                if (pipelineConfigOpt.isEmpty() || pipelineConfigOpt.get().pipelineSteps() == null) {
                    throw new PipelineConfigurationException("Pipeline or steps not found for: " + incomingPipeStream.getCurrentPipelineName() + " while preparing step " + originalTargetStepName);
                }
                stepConfig = pipelineConfigOpt.get().pipelineSteps().get(originalTargetStepName);
                if (stepConfig == null) {
                    throw new PipelineConfigurationException("Step config not found: " + originalTargetStepName + " in pipeline " + incomingPipeStream.getCurrentPipelineName());
                }
            } catch (PipelineConfigurationException pce) {
                log.error("SYNC FAILURE: Configuration error before step execution for target {}: {}", originalTargetStepName, pce.getMessage());
                handleFailedStepExecutionOrRouting(incomingPipeStream, originalTargetStepName, pce, "CONFIG_ERROR_PRE_EXECUTION");
                return;
            }

            int maxRetries = stepConfig.maxRetries() != null ? stepConfig.maxRetries() : 0;
            long baseRetryBackoffMs = stepConfig.retryBackoffMs() != null ? stepConfig.retryBackoffMs() : 1000L;
            long maxRetryBackoffMs = stepConfig.maxRetryBackoffMs() != null ? stepConfig.maxRetryBackoffMs() : 30000L;
            double retryMultiplier = stepConfig.retryBackoffMultiplier() != null && stepConfig.retryBackoffMultiplier() > 0 ? stepConfig.retryBackoffMultiplier() : 2.0;

            PipeStepExecutor executor;
            try {
                executor = executorFactory.getExecutor(
                        incomingPipeStream.getCurrentPipelineName(),
                        originalTargetStepName);
            } catch (Exception e) {
                log.error("SYNC FAILURE: Could not get executor for step {} (streamId: {}): {}", originalTargetStepName, streamId, e.getMessage());
                handleFailedStepExecutionOrRouting(incomingPipeStream, originalTargetStepName, e, "EXECUTOR_FACTORY_FAILURE");
                return;
            }

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                PipeStream currentInputStreamForAttempt = streamForNextProcessing;
                PipeStreamStateBuilder stateBuilderForAttempt = new PipeStreamStateBuilderImpl(currentInputStreamForAttempt, configManager);
                if (attempt == 0) {
                    stateBuilderForAttempt.withHopNumber((int) currentInputStreamForAttempt.getCurrentHopNumber() + 1);
                }
                PipeStream streamToExecuteThisAttempt = stateBuilderForAttempt.getPresentState().build();

                try {
                    log.info("Attempt {}/{} to execute step: {} for streamId: {}",
                            attempt + 1, maxRetries + 1, originalTargetStepName, streamId);
                    streamForNextProcessing = executor.execute(streamToExecuteThisAttempt);

                    if (streamForNextProcessing.getHistoryCount() == 0 ||
                            // Ensure the last history record is for the current step and hop
                            streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).getHopNumber() != streamToExecuteThisAttempt.getCurrentHopNumber() ||
                            !streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).getStepName().equals(originalTargetStepName)
                    ) {
                        log.error("CRITICAL: Executor for step {} (streamId: {}) did not add a valid history record for this attempt!", originalTargetStepName, streamId);
                        lastSeenExceptionFromExecutor = new PipeStepExecutionException("Executor failed to produce a valid history record for step " + originalTargetStepName, false);
                        stepExecutedSuccessfully = false; // Mark as not successful
                        break; // Exit retry loop, will be handled by if(!stepExecutedSuccessfully)
                    }
                    StepExecutionRecord lastAttemptRecord = streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1);

                    if ("SUCCESS".equals(lastAttemptRecord.getStatus())) {
                        stepExecutedSuccessfully = true;
                        log.info("Step {} executed successfully on attempt {} for streamId: {}",
                                originalTargetStepName, attempt + 1, streamId);
                        break;
                    } else { // Executor returned a PipeStream with non-SUCCESS status
                        log.warn("Step {} execution attempt {} indicated FAILURE (status: '{}') without throwing exception. StreamId: {}. Error: {}",
                                originalTargetStepName, attempt + 1, lastAttemptRecord.getStatus(), streamId,
                                lastAttemptRecord.hasErrorInfo() ? lastAttemptRecord.getErrorInfo().getErrorMessage() : "N/A");
                        lastSeenExceptionFromExecutor = new PipeStepExecutionException(
                                "Step " + originalTargetStepName + " reported status " + lastAttemptRecord.getStatus() +
                                        (lastAttemptRecord.hasErrorInfo() ? ": " + lastAttemptRecord.getErrorInfo().getErrorMessage() : ""),
                                false // Treat as non-retryable by the engine's loop
                        );
                        stepExecutedSuccessfully = false; // Mark as not successful
                        break; // <<<<< MODIFIED: Exit loop on clean FAILURE status from executor
                    }
                } catch (PipeStepExecutionException pse) {
                    lastSeenExceptionFromExecutor = pse;
                    // Use streamToExecuteThisAttempt as the base for recording this attempt's failure,
                    // as it has the correct hop number for this step's execution.
                    PipeStream.Builder currentAttemptFailureBuilder = streamToExecuteThisAttempt.toBuilder();
                    com.google.protobuf.Timestamp errorTimestamp = com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis());
                    StepExecutionRecord.Builder attemptFailureRecord = StepExecutionRecord.newBuilder()
                            .setHopNumber(streamToExecuteThisAttempt.getCurrentHopNumber()) // Use hop from streamToExecuteThisAttempt
                            .setStepName(originalTargetStepName)
                            .setStatus("ATTEMPT_FAILURE")
                            .setStartTime(errorTimestamp)
                            .setEndTime(errorTimestamp)
                            .setErrorInfo(ErrorData.newBuilder()
                                    .setOriginatingStepName(originalTargetStepName)
                                    .setErrorMessage("Attempt " + (attempt + 1) + " for step " + originalTargetStepName + " failed: " + pse.getMessage())
                                    .setErrorCode(pse.isRetryable() ? "RETRYABLE_STEP_ERROR" : "NON_RETRYABLE_STEP_ERROR")
                                    .setTechnicalDetails(pse.toString())
                                    .setTimestamp(errorTimestamp));
                    currentAttemptFailureBuilder.addHistory(attemptFailureRecord.build());
                    streamForNextProcessing = currentAttemptFailureBuilder.build();

                    log.warn("Attempt {}/{} for step {} (streamId: {}) failed with exception: {}. Retryable: {}",
                            attempt + 1, maxRetries + 1, originalTargetStepName, streamId, pse.getMessage(), pse.isRetryable());

                    if (pse.isRetryable() && attempt < maxRetries) {
                        long backoff = (long) (baseRetryBackoffMs * Math.pow(retryMultiplier, attempt));
                        backoff = Math.min(backoff, maxRetryBackoffMs);
                        log.info("Retrying step {} (streamId: {}) in {} ms (attempt {}/{})",
                                originalTargetStepName, streamId, backoff, attempt + 2, maxRetries + 1);
                        try {
                            TimeUnit.MILLISECONDS.sleep(backoff);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            log.warn("Retry backoff interrupted for step {} (streamId: {}). Failing.", originalTargetStepName, streamId);
                            PipeStream.Builder interruptedBuilder = streamForNextProcessing.toBuilder();
                            StepExecutionRecord.Builder interruptionRecord = StepExecutionRecord.newBuilder()
                                    .setHopNumber(streamToExecuteThisAttempt.getCurrentHopNumber())
                                    .setStepName(originalTargetStepName)
                                    .setStatus("ATTEMPT_INTERRUPTED")
                                    .setStartTime(errorTimestamp)
                                    .setEndTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()))
                                    .setErrorInfo(ErrorData.newBuilder()
                                            .setOriginatingStepName(originalTargetStepName)
                                            .setErrorMessage("Retry backoff interrupted")
                                            .setErrorCode("RETRY_INTERRUPTED")
                                            .setTechnicalDetails(ie.toString())
                                            .setTimestamp(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis())));
                            interruptedBuilder.addHistory(interruptionRecord.build());
                            streamForNextProcessing = interruptedBuilder.build();
                            lastSeenExceptionFromExecutor = new PipeStepExecutionException("Retry backoff interrupted", ie, false);
                            stepExecutedSuccessfully = false; // Ensure this is false
                            break; // Exit retry loop
                        }
                        // Continue to next iteration of the loop
                    } else {
                        log.error("Final attempt failed for step {} (streamId: {}) or error not retryable. Max retries: {}, Attempt: {}. Retryable: {}",
                                originalTargetStepName, streamId, maxRetries, attempt + 1, pse.isRetryable());
                        stepExecutedSuccessfully = false;
                        break;
                    }
                }
            } // End of retry loop

            if (!stepExecutedSuccessfully) {
                log.error("Step {} failed after all {} retries or due to a non-retryable error for streamId: {}",
                        originalTargetStepName, maxRetries, streamId);
                Throwable finalError = lastSeenExceptionFromExecutor;
                if (finalError == null) {
                    String lastStatusMsg = "Step " + originalTargetStepName + " failed (last status was not SUCCESS).";
                    if (streamForNextProcessing.getHistoryCount() > 0 &&
                            streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).hasErrorInfo()) {
                        lastStatusMsg = streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).getErrorInfo().getErrorMessage();
                    }
                    finalError = new PipeStepExecutionException(lastStatusMsg, false);
                }
                handleFailedStepExecutionOrRouting(streamForNextProcessing, originalTargetStepName,
                        finalError, "STEP_EXECUTION_RETRIES_EXHAUSTED_OR_NON_RETRYABLE");
                return;
            }

            // --- If step execution was successful (possibly after retries) ---
            PipeStreamStateBuilder finalStateBuilder = new PipeStreamStateBuilderImpl(streamForNextProcessing, configManager);
            List<RouteData> routes = finalStateBuilder.calculateNextRoutes();
            PipeStream streamStateForForwarding = finalStateBuilder.build();

            if (routes.isEmpty()) {
                log.info("No further routes to forward for streamId: {} from successfully executed step {}. Pipeline processing for this branch may be complete.",
                        streamId, originalTargetStepName);
                return;
            }

            List<CompletableFuture<?>> dispatchFutures = new ArrayList<>();
            for (RouteData route : routes) {
                log.debug("Preparing to route streamId: {} from executed step {} to destination: {}, nextActualStep: {}, transport: {}",
                        streamId, originalTargetStepName, route.destination(), route.nextTargetStep(), route.transportType());

                PipeStream.Builder destinationPipeBuilder = streamStateForForwarding.toBuilder()
                        .setTargetStepName(route.nextTargetStep())
                        .setCurrentPipelineName(route.targetPipeline());
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
                            .destination(route.destination())
                            .streamId(streamId)
                            .build();
                    CompletableFuture<Void> grpcFuture = grpcForwarder.forwardToGrpc(pipeToDispatch.toBuilder(), grpcRouteData);
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

        } catch (Exception e) { // This is the main try block's catch
            log.error("SYNC FAILURE: Critical error during execution of step {} or route calculation for streamId {}: {}",
                    originalTargetStepName, streamId, e.getMessage(), e);
            PipeStream streamForErrorHandling = (streamForNextProcessing != null && streamForNextProcessing.getHistoryCount() > incomingPipeStream.getHistoryCount()) ?
                    streamForNextProcessing : incomingPipeStream;
            handleFailedStepExecutionOrRouting(streamForErrorHandling, originalTargetStepName, e, "STEP_EXECUTION_OR_ROUTING_FAILURE");
        }
    } // End of executeStepAndForward method
    
    /**
     * Implementation of the PipeStreamEngine interface method.
     * This method processes a PipeStream by calling the internal executeStepAndForward method.
     * 
     * @param pipeStream The PipeStream to process
     * @throws IllegalArgumentException if pipeStream is null
     * @throws RuntimeException if processing fails
     */
    @Override
    public void processStream(PipeStream pipeStream) {
        if (pipeStream == null) {
            throw new IllegalArgumentException("Cannot process null PipeStream");
        }
        
        if (pipeStream.getTargetStepName() == null || pipeStream.getTargetStepName().isEmpty()) {
            throw new IllegalArgumentException("PipeStream must have a target step name");
        }
        
        log.info("Processing PipeStream via processStream method: streamId={}, targetStep={}", 
                pipeStream.getStreamId(), pipeStream.getTargetStepName());
        
        try {
            executeStepAndForward(pipeStream);
        } catch (Exception e) {
            log.error("Error processing PipeStream {}: {}", pipeStream.getStreamId(), e.getMessage(), e);
            // Re-throw as RuntimeException to propagate the error
            throw new RuntimeException("Failed to process PipeStream " + pipeStream.getStreamId() + 
                    " for step " + pipeStream.getTargetStepName(), e);
        }
    }
}