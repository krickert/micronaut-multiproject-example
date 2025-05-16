package com.krickert.search.engine.orchestration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.GrpcTransportConfig;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils; // Assuming this utility exists
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.PipeStepProcessorServiceProto; // Contains ProcessRequest, ProcessResponse

import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Singleton
public class SyncStepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(SyncStepExecutor.class);

    private final DynamicConfigurationManager configManager;
    private final GrpcClientProvider grpcClientProvider;

    @Inject
    public SyncStepExecutor(DynamicConfigurationManager configManager, GrpcClientProvider grpcClientProvider) {
        this.configManager = configManager;
        this.grpcClientProvider = grpcClientProvider;
    }

    public void executeStepSync(PipeStream request, StreamObserver<PipeStream> responseObserver) {
        LOG.info("Executing sync step: {} for stream_id: {}", request.getTargetStepName(), request.getStreamId());

        PipeStream.Builder responseStreamBuilder = request.toBuilder();
        long currentHop = request.getCurrentHopNumber() + 1;
        responseStreamBuilder.setCurrentHopNumber(currentHop);

        StepExecutionRecord.Builder currentStepHistory = StepExecutionRecord.newBuilder()
            .setHopNumber(currentHop)
            .setStepName(request.getTargetStepName())
            .setStartTime(Timestamps.fromMillis(System.currentTimeMillis()));

        try {
            Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(request.getCurrentPipelineName());
            if (pipelineConfig.isEmpty()) {
                buildAndSendErrorResponse(request, "Pipeline config not found: " + request.getCurrentPipelineName(),
                    "CONFIG_PIPELINE_NOT_FOUND", currentStepHistory, responseObserver);
                return;
            }
            PipelineStepConfig stepConfig = pipelineConfig.getStepsMap().get(request.getTargetStepName());
            if (stepConfig == null) {
                buildAndSendErrorResponse(request, "Step config not found: " + request.getTargetStepName() + " in pipeline " + request.getCurrentPipelineName(),
                    "CONFIG_STEP_NOT_FOUND", currentStepHistory, responseObserver);
                return;
            }

            if (stepConfig.getTransportConfigCase() != PipelineStepConfig.TransportConfigCase.GRPC_TRANSPORT_CONFIG) {
                buildAndSendErrorResponse(request, "Sync 'process' only supports GRPC steps. Step " + request.getTargetStepName() + " is not GRPC.",
                    "INVALID_TRANSPORT_FOR_SYNC", currentStepHistory, responseObserver);
                return;
            }
            GrpcTransportConfig grpcConfig = stepConfig.grpcConfig();
            String serviceName = grpcConfig.serviceId();

            PipeStepProcessorServiceProto.ProcessRequest.Builder processorRequestBuilder =
                PipeStepProcessorServiceProto.ProcessRequest.newBuilder()
                    .setDocument(request.getDocument());

            PipeStepProcessorServiceProto.ProcessConfiguration.Builder processConfigBuilder =
                PipeStepProcessorServiceProto.ProcessConfiguration.newBuilder();
            if (stepConfig.hasCustomConfigJson()) {
                processConfigBuilder.setCustomJsonConfig(stepConfig.getCustomConfigJson());
            }
            processConfigBuilder.putAllConfigParams(stepConfig.getConfigParamsMap());
            processorRequestBuilder.setConfig(processConfigBuilder);

            PipeStepProcessorServiceProto.ServiceMetadata.Builder serviceMetadataBuilder =
                PipeStepProcessorServiceProto.ServiceMetadata.newBuilder()
                    .setPipelineName(request.getCurrentPipelineName())
                    .setPipeStepName(request.getTargetStepName())
                    .setStreamId(request.getStreamId())
                    .setCurrentHopNumber(currentHop)
                    .addAllHistory(request.getHistoryList())
                    .putAllContextParams(request.getContextParamsMap());
            if (request.hasStreamErrorData()) {
                serviceMetadataBuilder.setStreamErrorData(request.getStreamErrorData());
            }
            processorRequestBuilder.setMetadata(serviceMetadataBuilder);

            PipeStepProcessorGrpc.PipeStepProcessorBlockingStub client = grpcClientProvider.getBlockingStub(serviceName);
            if (client == null) {
                buildAndSendErrorResponse(request, "Could not create gRPC client for service: " + serviceName,
                    "GRPC_CLIENT_FAILED", currentStepHistory, responseObserver);
                return;
            }

            LOG.info("Calling gRPC step: {} for stream_id: {}", serviceName, request.getStreamId());
            PipeStepProcessorServiceProto processorResponse;
            try {
                processorResponse = client.withDeadlineAfter(grpcConfig.getTimeoutSeconds() > 0 ? grpcConfig.getTimeoutSeconds() : 30, TimeUnit.SECONDS)
                                          .processData(processorRequestBuilder.build());
            } catch (Exception e) {
                LOG.error("gRPC call to {} failed for step {}", serviceName, request.getTargetStepName(), e);
                buildAndSendErrorResponse(request, "gRPC call failed: " + e.getMessage(),
                    "GRPC_CALL_FAILED", currentStepHistory, responseObserver, e.toString());
                return;
            }

            currentStepHistory.setEndTime(Timestamps.fromMillis(System.currentTimeMillis()));
            currentStepHistory.addAllProcessorLogs(processorResponse.getProcessorLogsList());

            if (processorResponse.getSuccess()) {
                currentStepHistory.setStatus("SUCCESS");
                if (processorResponse.hasOutputDoc()) {
                    responseStreamBuilder.setDocument(processorResponse.getOutputDoc());
                }
            } else {
                currentStepHistory.setStatus("FAILURE");
                ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                    .setErrorMessage("Step " + request.getTargetStepName() + " failed.")
                    .setOriginatingStepName(request.getTargetStepName())
                    .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
                if (processorResponse.hasErrorDetails()) {
                     try {
                        errorDataBuilder.setTechnicalDetails(ProtobufUtils.toJson(processorResponse.getErrorDetails()));
                    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                        LOG.warn("Failed to convert errorDetails Struct to JSON", e);
                        errorDataBuilder.setTechnicalDetails("Could not parse error_details Struct: " + e.getMessage());
                    }
                }
                currentStepHistory.setErrorInfo(errorDataBuilder);
            }
            responseStreamBuilder.addHistory(currentStepHistory.build());
            responseStreamBuilder.clearTargetStepName();

            responseObserver.onNext(responseStreamBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            LOG.error("Unexpected error in sync execution for stream_id: {}", request.getStreamId(), e);
            buildAndSendErrorResponse(request, "Unexpected internal error: " + e.getMessage(),
                "INTERNAL_ERROR", currentStepHistory, responseObserver, e.toString());
        }
    }

    private void buildAndSendErrorResponse(PipeStream request, String errorMessage, String errorCode,
                                   StepExecutionRecord.Builder currentStepHistoryBuilder,
                                   StreamObserver<PipeStream> responseObserver,
                                   String... technicalDetails) {
        LOG.error("Error executing sync step {} for stream_id {}: {}", request.getTargetStepName(), request.getStreamId(), errorMessage);
        currentStepHistoryBuilder.setStatus("FAILURE")
            .setEndTime(Timestamps.fromMillis(System.currentTimeMillis()));
        ErrorData.Builder errorInfo = ErrorData.newBuilder()
            .setErrorMessage(errorMessage)
            .setErrorCode(errorCode)
            .setOriginatingStepName(request.getTargetStepName()) // The step that was attempted
            .setTimestamp(Timestamps.fromMillis(System.currentTimeMillis()));
        if (technicalDetails.length > 0 && technicalDetails[0] != null) {
            errorInfo.setTechnicalDetails(technicalDetails[0]);
        }
        currentStepHistoryBuilder.setErrorInfo(errorInfo);

        PipeStream errorResponse = request.toBuilder()
            .setCurrentHopNumber(currentStepHistoryBuilder.getHopNumber()) // ensure hop number is updated
            .clearHistory() // Clear existing history and add just this attempt
            .addHistory(currentStepHistoryBuilder.build())
            .clearTargetStepName() // Indicate no further step from this call
            .build();
        responseObserver.onNext(errorResponse);
        responseObserver.onCompleted();
    }
}