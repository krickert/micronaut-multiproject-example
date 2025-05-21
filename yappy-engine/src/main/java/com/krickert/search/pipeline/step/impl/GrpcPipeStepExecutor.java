package com.krickert.search.pipeline.step.impl;

import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import com.krickert.search.sdk.ProcessResponse;

import java.time.Instant;

/**
 * Implementation of PipeStepExecutor that executes steps via gRPC.
 */
public class GrpcPipeStepExecutor implements PipeStepExecutor {
    private final PipelineStepGrpcProcessor grpcProcessor;
    private final String stepName;
    private final StepType stepType;

    // Utility for converting Struct to JSON, initialized lazily or statically
    private static final JsonFormat.Printer jsonPrinter = JsonFormat.printer().preservingProtoFieldNames();

    public GrpcPipeStepExecutor(PipelineStepGrpcProcessor grpcProcessor,
                               String stepName,
                               StepType stepType) {
        this.grpcProcessor = grpcProcessor;
        this.stepName = stepName;
        this.stepType = stepType;
    }

    @Override
    public PipeStream execute(PipeStream pipeStream) throws PipeStepExecutionException {
        Instant now = Instant.now();
        Timestamp startTime = Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build();
        ProcessResponse response;

        try {
            response = grpcProcessor.processStep(pipeStream, stepName);
        } catch (Exception e) {
            // If the gRPC call itself fails, we still want to record a failure.
            Instant errorTime = Instant.now();
            Timestamp endTime = Timestamp.newBuilder().setSeconds(errorTime.getEpochSecond()).setNanos(errorTime.getNano()).build();
            // Create a synthetic ProcessResponse to represent the gRPC call failure
            ProcessResponse.Builder errorResponseBuilder = ProcessResponse.newBuilder()
                .setSuccess(false)
                .addProcessorLogs("gRPC call failed for step: " + stepName + ". Error: " + e.getMessage());
            
            // Optionally, try to populate error_details if possible, or leave it empty
            // For simplicity, we'll just use the exception message in logs.

            return transformResponseToPipeStream(pipeStream, errorResponseBuilder.build(), startTime, endTime, e);
        }
        
        Instant afterCall = Instant.now();
        Timestamp endTime = Timestamp.newBuilder().setSeconds(afterCall.getEpochSecond()).setNanos(afterCall.getNano()).build();
        return transformResponseToPipeStream(pipeStream, response, startTime, endTime, null);
    }

    @Override
    public String getStepName() {
        return stepName;
    }

    @Override
    public StepType getStepType() {
        return stepType;
    }

    private PipeStream transformResponseToPipeStream(PipeStream original,
                                                     ProcessResponse response,
                                                     Timestamp startTime,
                                                     Timestamp endTime,
                                                     Exception grpcCallException) { // Used if the gRPC call itself failed
        PipeStream.Builder pipeStreamBuilder = original.toBuilder();

        // Update the document if provided in the response
        if (response.hasOutputDoc()) {
            pipeStreamBuilder.setDocument(response.getOutputDoc());
        }

        // Create and add the StepExecutionRecord
        StepExecutionRecord.Builder recordBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(original.getCurrentHopNumber()) // The hop number for *this* step
                .setStepName(this.stepName)
                .setStartTime(startTime)
                .setEndTime(endTime)
                .setStatus(response.getSuccess() ? "SUCCESS" : "FAILURE")
                .addAllProcessorLogs(response.getProcessorLogsList());

        if (!response.getSuccess()) {
            ErrorData.Builder errorDataBuilder = ErrorData.newBuilder()
                    .setOriginatingStepName(this.stepName)
                    .setTimestamp(endTime);

            if (grpcCallException != null) {
                errorDataBuilder.setErrorMessage("gRPC call execution failed for step: " + this.stepName);
                errorDataBuilder.setErrorCode("GRPC_EXECUTION_FAILURE");
                errorDataBuilder.setTechnicalDetails(grpcCallException.toString());
            } else if (response.hasErrorDetails()) {
                com.google.protobuf.Struct errorDetailsStruct = response.getErrorDetails();
                // Attempt to extract common fields, providing defaults
                String message = errorDetailsStruct.getFieldsOrDefault("message",
                        com.google.protobuf.Value.newBuilder().setStringValue("Processor reported an error.").build()
                ).getStringValue();
                String code = errorDetailsStruct.getFieldsOrDefault("code",
                        com.google.protobuf.Value.newBuilder().setStringValue("PROCESSOR_FAILURE").build()
                ).getStringValue();

                errorDataBuilder.setErrorMessage(message);
                errorDataBuilder.setErrorCode(code);
                try {
                    // Store the full error_details struct as a JSON string for more detailed diagnostics
                    errorDataBuilder.setTechnicalDetails(jsonPrinter.print(errorDetailsStruct));
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    errorDataBuilder.setTechnicalDetails("Failed to serialize error_details to JSON: " + e.getMessage());
                }
            } else {
                // Generic error if success is false but no error_details provided
                errorDataBuilder.setErrorMessage("Processor step " + this.stepName + " failed without detailed error information.");
                errorDataBuilder.setErrorCode("PROCESSOR_GENERIC_FAILURE");
            }
            recordBuilder.setErrorInfo(errorDataBuilder.build());
        }

        pipeStreamBuilder.addHistory(recordBuilder.build());

        // Note: Updating PipeStream.stream_error_data is a higher-level concern.
        // If this step's failure should halt the entire stream, the PipeStreamEngineImpl
        // would typically set stream_error_data after this executor returns.

        return pipeStreamBuilder.build();
    }
}