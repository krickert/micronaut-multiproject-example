//package com.krickert.search.engine.orchestration;
//
//import com.google.protobuf.util.Timestamps;
//import com.krickert.search.config.consul.DynamicConfigurationManager;
//import com.krickert.search.config.pipeline.model.PipelineConfig;
//import com.krickert.search.config.pipeline.model.PipelineStepConfig;
//import com.krickert.search.config.pipeline.model.KafkaTransportConfig;
//import com.krickert.search.config.pipeline.model.TransportType;
//import com.krickert.search.engine.service.PipeStreamDispatcher; // Kafka dispatcher interface
//
//import com.google.protobuf.Empty;
//import com.krickert.search.model.ErrorData;
//import com.krickert.search.model.PipeStream;
//import com.krickert.search.model.StepExecutionRecord;
//import io.grpc.Status;
//import io.grpc.stub.StreamObserver;
//import jakarta.inject.Inject;
//import jakarta.inject.Singleton;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//
//@Singleton
//public class AsyncPipelineProcessor {
//
//    private static final Logger LOG = LoggerFactory.getLogger(AsyncPipelineProcessor.class);
//
//    private final DynamicConfigurationManager configManager;
//    private final PipeStreamDispatcher pipeStreamDispatcher;
//
//    @Inject
//    public AsyncPipelineProcessor(DynamicConfigurationManager configManager, PipeStreamDispatcher pipeStreamDispatcher) {
//        this.configManager = configManager;
//        this.pipeStreamDispatcher = pipeStreamDispatcher;
//    }
//
//    public void processAndDispatchAsync(PipeStream request, StreamObserver<Empty> responseObserver) {
//        LOG.info("Async processing for stream_id: {}, target_step: {}", request.getStreamId(), request.getTargetStepName());
//
//        PipeStream.Builder currentStreamBuilder = request.toBuilder();
//        long nextHopNumber = request.getCurrentHopNumber() + 1; // This hop is for the target_step_name
//
//        // Initialize history builder for the step we are about to dispatch to
//        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
//            .setHopNumber(nextHopNumber)
//            .setStepName(request.getTargetStepName())
//            .setStartTime(Timestamps.fromMillis(System.currentTimeMillis()));
//        // Set status to dispatching initially, it will be updated by actual step execution later
//        historyBuilder.setStatus("DISPATCHING_TO_KAFKA");
//
//
//        try {
//            currentStreamBuilder.setCurrentHopNumber(nextHopNumber); // Update hop number on the stream
//
//            PipelineConfig pipelineConfig = configManager.getCurrentPipelineClusterConfig().get().pipelineGraphConfig().getPipelineConfig(request.getCurrentPipelineName());
//            if (pipelineConfig == null) {
//                handleProcessingError(currentStreamBuilder, historyBuilder,
//                    "Pipeline config not found: " + request.getCurrentPipelineName(),
//                    "CONFIG_ERROR_PIPELINE_NOT_FOUND", request.getTargetStepName(), responseObserver);
//                return;
//            }
//
//            PipelineStepConfig stepConfig = pipelineConfig.pipelineSteps().get(request.getTargetStepName());
//            if (stepConfig == null) {
//                handleProcessingError(currentStreamBuilder, historyBuilder,
//                    "Step config not found: " + request.getTargetStepName() + " in pipeline " + request.getCurrentPipelineName(),
//                    "CONFIG_ERROR_STEP_NOT_FOUND", request.getTargetStepName(), responseObserver);
//                return;
//            }
//
//            // Add history record for dispatch attempt
//            currentStreamBuilder.addHistory(historyBuilder.build()); // Add the "DISPATCHING" record
//            PipeStream streamToDispatch = currentStreamBuilder.build();
//
//            //TODO - this should be handled through a router service
/// /            switch (stepConfig.transportType()) {
/// /                case KAFKA ->
/// /            }
//            if (stepConfig.transportType() == TransportType.KAFKA) {
//                KafkaTransportConfig kafkaConfig = stepConfig.kafkaConfig();
//                if (kafkaConfig.publishTopicPattern() == null || kafkaConfig.publishTopicPattern().isEmpty()) {
//                     handleProcessingError(currentStreamBuilder, historyBuilder, // Use streamToDispatch's builder
//                        "Kafka topic not configured for step: " + request.getTargetStepName(),
//                        "CONFIG_ERROR_KAFKA_TOPIC_MISSING", request.getTargetStepName(), responseObserver);
//                     return;
//                }
//                pipeStreamDispatcher.dispatch(kafkaConfig.publishTopicPattern(), streamToDispatch);
//                LOG.info("Dispatched stream_id: {} to Kafka topic: {} for step: {}",
//                    streamToDispatch.getStreamId(), kafkaConfig.publishTopicPattern(), streamToDispatch.getTargetStepName());
//            } else {
//                handleProcessingError(currentStreamBuilder, historyBuilder, // Use streamToDispatch's builder
//                    "Unsupported transport type for async dispatch: " + stepConfig.transportType() + " for step: " + request.getTargetStepName(),
//                    "CONFIG_ERROR_UNSUPPORTED_TRANSPORT", request.getTargetStepName(), responseObserver);
//                return;
//            }
//
//            responseObserver.onNext(Empty.newBuilder().build());
//            responseObserver.onCompleted();
//
//        } catch (Exception e) {
//            LOG.error("Error in processAndDispatchAsync for stream_id: {}", request.getStreamId(), e);
//            // Update history with failure before responding to observer
//            historyBuilder.setStatus("DISPATCH_FAILED")
//                          .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()));
//            ErrorData.Builder errorData = ErrorData.newBuilder()
//                    .setErrorMessage("Failed to dispatch: " + e.getMessage())
//                    .setErrorCode("DISPATCH_EXCEPTION")
//                    .setOriginatingStepName(historyBuilder.getStepName()) // step being attempted
//                    .setTechnicalDetails(e.toString())
//                    .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
//            historyBuilder.setErrorInfo(errorData);
//
//            // Ensure this failed history is part of the stream if an error occurs before onError is called
//            // Note: currentStreamBuilder might not have the latest history if error happened before build()
//            // It's safer to respond with error directly to the observer for gRPC call context
//             responseObserver.onError(Status.INTERNAL
//                .withDescription("Failed to process and dispatch asynchronously: " + e.getMessage())
//                .withCause(e)
//                .asRuntimeException());
//        }
//    }
//
//    private void handleProcessingError(PipeStream.Builder streamBuilder,
//                                       StepExecutionRecord.Builder incompleteHistoryBuilder,
//                                       String errorMessage, String errorCode, String stepName,
//                                       StreamObserver<Empty> responseObserver) {
//        LOG.error(errorMessage);
//
//        incompleteHistoryBuilder.setStatus("DISPATCH_SETUP_FAILURE") // More specific status
//                      .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()));
//        ErrorData.Builder errorData = ErrorData.newBuilder()
//                .setErrorMessage(errorMessage)
//                .setErrorCode(errorCode)
//                .setOriginatingStepName(stepName) // Use the stepName passed in
//                .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
//        incompleteHistoryBuilder.setErrorInfo(errorData);
//
//        // Add the failed history record. streamBuilder already has its hop_number incremented.
//        streamBuilder.addHistory(incompleteHistoryBuilder.build());
//        streamBuilder.clearTargetStepName(); // Stop further processing for this path
//        streamBuilder.setStreamErrorData(errorData.build()); // Mark stream as failed
//
//        // The original contract of processAsync is to return Empty even if pipeline setup fails,
//        // as the error is logged and stream is marked.
//        // However, for config errors that prevent dispatch, failing the gRPC call might be more informative.
//        responseObserver.onError(Status.FAILED_PRECONDITION.withDescription(errorMessage).asRuntimeException());
//    }
//}