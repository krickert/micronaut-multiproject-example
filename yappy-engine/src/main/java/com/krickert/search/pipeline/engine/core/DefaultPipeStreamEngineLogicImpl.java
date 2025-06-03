// Suggested package: com.krickert.search.pipeline.engine.core (or similar)
package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.engine.PipeStreamEngine; // Your interface
import com.krickert.search.pipeline.engine.common.RouteData;
import com.krickert.search.pipeline.engine.exception.PipelineConfigurationException;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder; // Needed if core logic dispatches gRPC
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilder;
import com.krickert.search.pipeline.engine.state.PipeStreamStateBuilderImpl;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.Timestamps;
import io.grpc.stub.StreamObserver;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Singleton // This will be the bean that gets injected when PipeStreamEngine is requested
public class DefaultPipeStreamEngineLogicImpl implements PipeStreamEngine {
    private static final Logger log = LoggerFactory.getLogger(DefaultPipeStreamEngineLogicImpl.class);

    private final PipeStepExecutorFactory executorFactory;
    private final PipeStreamGrpcForwarder grpcForwarder; // Core logic needs to dispatch to gRPC
    private final KafkaForwarder kafkaForwarder;
    private final DynamicConfigurationManager configManager;

    // Private static record for ConnectorEntryPoint if it's only used internally here
    private record ConnectorEntryPoint(String pipelineName, String stepName) {}


    @Inject
    public DefaultPipeStreamEngineLogicImpl(
            PipeStepExecutorFactory executorFactory,
            PipeStreamGrpcForwarder grpcForwarder, // Keep if core logic makes gRPC calls
            KafkaForwarder kafkaForwarder,
            DynamicConfigurationManager configManager) {
        this.executorFactory = executorFactory;
        this.grpcForwarder = grpcForwarder;
        this.kafkaForwarder = kafkaForwarder;
        this.configManager = configManager;
    }

    @Override
    public void processStream(PipeStream pipeStream) {
        // This is the method from your PipeStreamEngine interface.
        // It will now call the main processing logic.
        log.debug("DefaultPipeStreamEngineLogicImpl.processStream called for streamId: {}", pipeStream.getStreamId());
        executeStepAndForward(pipeStream);
    }
    
    private void executeStepAndForward(PipeStream incomingPipeStream) {
        String streamId = incomingPipeStream.getStreamId();
        String originalTargetStepName = incomingPipeStream.getTargetStepName();
        log.info("CoreLogic: Executing step {} for streamId: {}", originalTargetStepName, streamId);
        
        PipeStream streamForNextProcessing = incomingPipeStream;
        boolean stepExecutedSuccessfully = false;
        PipeStepExecutionException lastSeenExceptionFromExecutor = null;

        PipelineStepConfig stepConfig;
        try {
            // Get pipeline and step configuration
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

        // Get retry configuration
        int maxRetries = stepConfig.maxRetries() != null ? stepConfig.maxRetries() : 0;
        long baseRetryBackoffMs = stepConfig.retryBackoffMs() != null ? stepConfig.retryBackoffMs() : 1000L;
        long maxRetryBackoffMs = stepConfig.maxRetryBackoffMs() != null ? stepConfig.maxRetryBackoffMs() : 30000L;
        double retryMultiplier = stepConfig.retryBackoffMultiplier() != null && stepConfig.retryBackoffMultiplier() > 0 ? stepConfig.retryBackoffMultiplier() : 2.0;

        // Get the executor for this step
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

        // Retry loop for step execution
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

                // Validate that executor added a history record
                if (streamForNextProcessing.getHistoryCount() == 0 ||
                        streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).getHopNumber() != streamToExecuteThisAttempt.getCurrentHopNumber() ||
                        !streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1).getStepName().equals(originalTargetStepName)) {
                    log.error("CRITICAL: Executor for step {} (streamId: {}) did not add a valid history record for this attempt!", originalTargetStepName, streamId);
                    lastSeenExceptionFromExecutor = new PipeStepExecutionException("Executor failed to produce a valid history record for step " + originalTargetStepName, false);
                    stepExecutedSuccessfully = false;
                    break;
                }
                
                StepExecutionRecord lastAttemptRecord = streamForNextProcessing.getHistory(streamForNextProcessing.getHistoryCount() - 1);

                if ("SUCCESS".equals(lastAttemptRecord.getStatus())) {
                    stepExecutedSuccessfully = true;
                    log.info("Step {} executed successfully on attempt {} for streamId: {}",
                            originalTargetStepName, attempt + 1, streamId);
                    break;
                } else {
                    log.warn("Step {} execution attempt {} indicated FAILURE (status: '{}') without throwing exception. StreamId: {}. Error: {}",
                            originalTargetStepName, attempt + 1, lastAttemptRecord.getStatus(), streamId,
                            lastAttemptRecord.hasErrorInfo() ? lastAttemptRecord.getErrorInfo().getErrorMessage() : "N/A");
                    lastSeenExceptionFromExecutor = new PipeStepExecutionException(
                            "Step " + originalTargetStepName + " reported status " + lastAttemptRecord.getStatus() +
                                    (lastAttemptRecord.hasErrorInfo() ? ": " + lastAttemptRecord.getErrorInfo().getErrorMessage() : ""),
                            false
                    );
                    stepExecutedSuccessfully = false;
                    break;
                }
            } catch (PipeStepExecutionException pse) {
                lastSeenExceptionFromExecutor = pse;
                handleRetryableException(streamToExecuteThisAttempt, originalTargetStepName, pse, attempt, maxRetries, baseRetryBackoffMs, maxRetryBackoffMs, retryMultiplier);
                
                if (!pse.isRetryable() || attempt >= maxRetries) {
                    stepExecutedSuccessfully = false;
                    break;
                }
                // Continue to next retry attempt
            }
        }

        if (!stepExecutedSuccessfully) {
            log.error("Step {} failed after all {} retries or due to a non-retryable error for streamId: {}",
                    originalTargetStepName, maxRetries, streamId);
            Throwable finalError = lastSeenExceptionFromExecutor != null ? lastSeenExceptionFromExecutor : 
                    new PipeStepExecutionException("Step " + originalTargetStepName + " failed", false);
            handleFailedStepExecutionOrRouting(streamForNextProcessing, originalTargetStepName,
                    finalError, "STEP_EXECUTION_RETRIES_EXHAUSTED_OR_NON_RETRYABLE");
            return;
        }

        // Step executed successfully - now calculate and execute routing
        PipeStreamStateBuilder finalStateBuilder = new PipeStreamStateBuilderImpl(streamForNextProcessing, configManager);
        List<RouteData> routes = finalStateBuilder.calculateNextRoutes();
        PipeStream streamStateForForwarding = finalStateBuilder.build();

        if (routes.isEmpty()) {
            log.info("No further routes to forward for streamId: {} from successfully executed step {}. Pipeline processing for this branch may be complete.",
                    streamId, originalTargetStepName);
            return;
        }

        // Forward to all routes asynchronously
        List<CompletableFuture<?>> dispatchFutures = new ArrayList<>();
        for (RouteData route : routes) {
            log.debug("Preparing to route streamId: {} from executed step {} to destination: {}, nextTargetStep: {}, transport: {}",
                    streamId, originalTargetStepName, route.destination(), route.nextTargetStep(), route.transportType());

            // Create a new PipeStream for each destination with the correct target step
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
                        handleFailedDispatch(streamStateForForwarding, route, ex, "KAFKA_FORWARD_FAILURE");
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
                        handleFailedDispatch(streamStateForForwarding, route, ex, "GRPC_FORWARD_FAILURE");
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
    }

    // Placeholder for findConnectorStepDetailsByIdentifier - MOVE THE ACTUAL IMPLEMENTATION HERE
    private ConnectorEntryPoint findConnectorStepDetailsByIdentifier(String sourceIdentifier) throws PipelineConfigurationException {
        log.debug("CoreLogic: Finding connector step details for identifier: {}", sourceIdentifier);
        // ... PASTE YOUR LOGIC ...
        Optional<PipelineClusterConfig> clusterConfigOpt = configManager.getCurrentPipelineClusterConfig();
        if (clusterConfigOpt.isEmpty()) {
            throw new PipelineConfigurationException("No cluster configuration found. Cannot find connector step: " + sourceIdentifier);
        }
        PipelineClusterConfig clusterConfig = clusterConfigOpt.get();
        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipelineConfig = pipelineEntry.getValue();
            if (pipelineConfig.pipelineSteps().containsKey(sourceIdentifier)) {
                return new ConnectorEntryPoint(pipelineName, sourceIdentifier);
            }
        }
        throw new PipelineConfigurationException("No PipeStepConfig found with name matching source identifier: " + sourceIdentifier);
    }
    
    /**
     * Handles a retryable exception during step execution by updating the PipeStream with error information.
     */
    private void handleRetryableException(PipeStream streamToExecuteThisAttempt, String stepName, 
                                        PipeStepExecutionException pse, int attempt, int maxRetries,
                                        long baseRetryBackoffMs, long maxRetryBackoffMs, double retryMultiplier) {
        // Log the retry attempt
        log.warn("Attempt {}/{} for step {} (streamId: {}) failed with exception: {}. Retryable: {}",
                attempt + 1, maxRetries + 1, stepName, streamToExecuteThisAttempt.getStreamId(), 
                pse.getMessage(), pse.isRetryable());
        
        if (pse.isRetryable() && attempt < maxRetries) {
            long backoff = (long) (baseRetryBackoffMs * Math.pow(retryMultiplier, attempt));
            backoff = Math.min(backoff, maxRetryBackoffMs);
            log.info("Will retry step {} (streamId: {}) in {} ms (attempt {}/{})",
                    stepName, streamToExecuteThisAttempt.getStreamId(), backoff, attempt + 2, maxRetries + 1);
            
            try {
                TimeUnit.MILLISECONDS.sleep(backoff);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                log.warn("Retry backoff interrupted for step {} (streamId: {}). Failing.", 
                        stepName, streamToExecuteThisAttempt.getStreamId());
            }
        }
    }
    
    /**
     * Handles failed step execution or routing calculation by sending the stream to an error topic.
     */
    private void handleFailedStepExecutionOrRouting(PipeStream streamStateAtFailure, String failedStepName, 
                                                  Throwable executionException, String errorCode) {
        String streamId = streamStateAtFailure.getStreamId();
        log.error("Handling failed step execution or routing calculation for streamId: {}. Failed Step: {}, ErrorCode: {}, Exception: {}",
                streamId, failedStepName, errorCode, executionException.getMessage());

        PipeStream.Builder errorPipeBuilder = streamStateAtFailure.toBuilder();
        Timestamp errorTimestamp = Timestamps.fromMillis(System.currentTimeMillis());

        ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                .setErrorMessage("Step execution or routing calculation failed for " + failedStepName + ": " + executionException.getMessage())
                .setErrorCode(errorCode)
                .setOriginatingStepName(failedStepName)
                .setTechnicalDetails(executionException.toString())
                .setTimestamp(errorTimestamp);

        // Mark the entire stream as failed
        errorPipeBuilder.setStreamErrorData(errorDataBuilder.build());
        errorPipeBuilder.clearTargetStepName(); // Stop further processing for this path

        // Add error record to history
        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(streamStateAtFailure.getCurrentHopNumber())
                .setStepName(failedStepName)
                .setStatus("ORCHESTRATION_ERROR_HALT")
                .setStartTime(errorTimestamp)
                .setEndTime(errorTimestamp)
                .setErrorInfo(errorDataBuilder.build());
        errorPipeBuilder.addHistory(historyBuilder.build());

        PipeStream streamForDlq = errorPipeBuilder.build();
        String errorRoutingTopic = "pipeline.errors." + streamStateAtFailure.getCurrentPipelineName();
        log.warn("Routing streamId {} to error topic {} due to synchronous step/routing failure.", streamId, errorRoutingTopic);
        kafkaForwarder.forwardToErrorTopic(streamForDlq, errorRoutingTopic);
    }
    
    /**
     * Handles failed dispatch (either Kafka or gRPC) by sending the stream to an error topic.
     */
    private void handleFailedDispatch(PipeStream dispatchedStream, RouteData failedRoute, 
                                    Throwable dispatchException, String errorCode) {
        String streamId = dispatchedStream.getStreamId();
        log.error("Handling dispatch failure for streamId: {} to destination: {} via {}. Exception: {}",
                streamId, failedRoute.destination(), failedRoute.transportType(), dispatchException.getMessage());

        PipeStream.Builder errorPipeBuilder = dispatchedStream.toBuilder();
        Timestamp errorTimestamp = Timestamps.fromMillis(System.currentTimeMillis());

        ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                .setErrorMessage("Failed to dispatch to " + failedRoute.destination() + " for step " + failedRoute.nextTargetStep() + ": " + dispatchException.getMessage())
                .setErrorCode(errorCode)
                .setOriginatingStepName(dispatchedStream.getTargetStepName())
                .setAttemptedTargetStepName(failedRoute.nextTargetStep())
                .setTechnicalDetails(dispatchException.toString())
                .setTimestamp(errorTimestamp);

        // Add dispatch failure record to history
        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(dispatchedStream.getCurrentHopNumber())
                .setStepName(dispatchedStream.getTargetStepName())
                .setAttemptedTargetStepName(failedRoute.nextTargetStep())
                .setStatus("DISPATCH_FAILURE")
                .setStartTime(errorTimestamp)
                .setEndTime(errorTimestamp)
                .setErrorInfo(errorDataBuilder.build());

        errorPipeBuilder.addHistory(historyBuilder.build());

        PipeStream streamForDlq = errorPipeBuilder.build();
        String errorRoutingTopic = "pipeline.errors." + dispatchedStream.getCurrentPipelineName();
        log.warn("Routing streamId {} (after dispatch failure to {}) to error topic {}", streamId, failedRoute.destination(), errorRoutingTopic);
        kafkaForwarder.forwardToErrorTopic(streamForDlq, errorRoutingTopic);
    }

}