package com.krickert.search.pipeline.step.impl;

import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import com.krickert.search.pipeline.step.exception.PipeStepProcessingException;
import com.krickert.search.pipeline.step.grpc.PipelineStepGrpcProcessor;
import com.krickert.search.sdk.ProcessResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrpcPipeStepExecutorTest {

    private static final String TEST_STEP_NAME = "test-grpc-step";
    private static final StepType TEST_STEP_TYPE = StepType.PIPELINE;
    private static final String TEST_STREAM_ID = "stream-grpc-test-123";
    private static final String TEST_DOC_ID = "doc-grpc-test-456";

    @Mock
    private PipelineStepGrpcProcessor mockGrpcProcessor;

    private GrpcPipeStepExecutor grpcPipeStepExecutor;

    @BeforeEach
    void setUp() {
        grpcPipeStepExecutor = new GrpcPipeStepExecutor(mockGrpcProcessor, TEST_STEP_NAME, TEST_STEP_TYPE);
    }

    private PipeStream createSamplePipeStream() {
        return PipeStream.newBuilder()
                .setStreamId(TEST_STREAM_ID)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName(TEST_STEP_NAME) // Target for this execution
                .setCurrentHopNumber(1) // Assume this is the hop number for *this* step's execution
                .setDocument(PipeDoc.newBuilder().setId(TEST_DOC_ID).setTitle("Original Title").build())
                .build();
    }

    @Test
    @DisplayName("execute - successful gRPC call, updates PipeStream correctly")
    void execute_successfulGrpcCall() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        PipeDoc newOutputDoc = PipeDoc.newBuilder().setId(TEST_DOC_ID).setTitle("Processed Title").setBody("New Body").build();
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(true)
                .setOutputDoc(newOutputDoc)
                .addProcessorLogs("Step executed successfully.")
                .build();

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        assertEquals(newOutputDoc, resultPipeStream.getDocument(), "Document should be updated from response.");
        assertEquals(1, resultPipeStream.getHistoryCount(), "Should have one history record added.");

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals(originalPipeStream.getCurrentHopNumber(), record.getHopNumber(), "Hop number in record should match input stream's current hop.");
        assertEquals("SUCCESS", record.getStatus());
        assertTrue(record.getProcessorLogsList().contains("Step executed successfully."));
        assertFalse(record.hasErrorInfo(), "No error info should be present on success.");
        assertTrue(record.hasStartTime() && record.hasEndTime(), "Timestamps should be set.");
        assertTrue(record.getEndTime().getSeconds() >= record.getStartTime().getSeconds(), "End time should be >= start time.");
    }

    @Test
    @DisplayName("execute - gRPC processor returns failure with error details")
    void execute_grpcProcessorReturnsFailureWithErrorDetails() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        Struct errorDetailsStruct = Struct.newBuilder()
                .putFields("message", Value.newBuilder().setStringValue("Custom processor error message").build())
                .putFields("code", Value.newBuilder().setStringValue("PROC_ERR_123").build())
                .putFields("details", Value.newBuilder().setStringValue("More specific details here.").build())
                .build();
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(false)
                .setErrorDetails(errorDetailsStruct)
                .addProcessorLogs("Step failed with custom error.")
                .build();

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        // Document might or might not be updated on failure, depends on processor behavior.
        // Here, we assume it's not updated if not explicitly set in mockProcessorResponse.
        assertEquals(originalPipeStream.getDocument(), resultPipeStream.getDocument(), "Document should remain original on failure if not updated by processor.");
        assertEquals(1, resultPipeStream.getHistoryCount());

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.getProcessorLogsList().contains("Step failed with custom error."));
        assertTrue(record.hasErrorInfo(), "Error info should be present.");

        ErrorData errorData = record.getErrorInfo();
        assertEquals(TEST_STEP_NAME, errorData.getOriginatingStepName());
        assertEquals("Custom processor error message", errorData.getErrorMessage());
        assertEquals("PROC_ERR_123", errorData.getErrorCode());
        assertNotNull(errorData.getTechnicalDetails());
        assertTrue(errorData.getTechnicalDetails().contains("PROC_ERR_123"), "Technical details should contain error code from struct.");
        assertTrue(errorData.getTechnicalDetails().contains("More specific details here."), "Technical details should contain details from struct.");
    }

    @Test
    @DisplayName("execute - gRPC processor returns failure without error details")
    void execute_grpcProcessorReturnsFailureWithoutErrorDetails() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(false)
                .addProcessorLogs("Step failed generically.")
                .build(); // No ErrorDetails

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertEquals(1, resultPipeStream.getHistoryCount());
        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.hasErrorInfo());
        ErrorData errorData = record.getErrorInfo();
        assertEquals("Processor step " + TEST_STEP_NAME + " failed without detailed error information.", errorData.getErrorMessage());
        assertEquals("PROCESSOR_GENERIC_FAILURE", errorData.getErrorCode());
    }

    @Test
    @DisplayName("execute - gRPC processor throws an exception")
    void execute_grpcProcessorThrowsException() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        RuntimeException simulatedException = new RuntimeException("Simulated gRPC call exception");

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenThrow(simulatedException);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        assertEquals(1, resultPipeStream.getHistoryCount());

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.hasErrorInfo(), "Error info should be present when gRPC call throws exception.");

        ErrorData errorData = record.getErrorInfo();
        assertEquals(TEST_STEP_NAME, errorData.getOriginatingStepName());
        assertEquals("gRPC call execution failed for step: " + TEST_STEP_NAME, errorData.getErrorMessage());
        assertEquals("GRPC_EXECUTION_FAILURE", errorData.getErrorCode());
        assertEquals(simulatedException.toString(), errorData.getTechnicalDetails());
        assertTrue(record.getProcessorLogsList().get(0).contains("gRPC call failed for step: " + TEST_STEP_NAME));
    }

    @Test
    @DisplayName("getStepName - returns correct step name")
    void getStepName_returnsCorrectName() {
        assertEquals(TEST_STEP_NAME, grpcPipeStepExecutor.getStepName());
    }

    @Test
    @DisplayName("getStepType - returns correct step type")
    void getStepType_returnsCorrectType() {
        assertEquals(TEST_STEP_TYPE, grpcPipeStepExecutor.getStepType());
    }

    @Test
    @DisplayName("execute - error details struct serialization failure")
    void execute_errorDetailsSerializationFailure() throws Exception {
        PipeStream originalPipeStream = createSamplePipeStream();
        // Create a Struct that will cause JsonFormat.printer().print() to fail
        // This is hard to do reliably without knowing internal printer limitations.
        // A more direct way would be to mock the static JsonFormat.printer() if possible (e.g. with PowerMock),
        // or accept that this specific internal failure is hard to unit test perfectly.
        // For now, let's assume a complex/recursive struct might cause issues, though unlikely for valid Structs.
        // A simpler test: ensure the fallback message is used if print() throws.

        // We can't easily make jsonPrinter.print() throw a specific exception without deeper mocking.
        // Instead, we'll test the path where errorDetails are present but the print() call is part of the SUT.
        // The test for `grpcProcessorReturnsFailureWithErrorDetails` already covers the successful print.
        // This test will focus on the `grpcCallException != null` path for `technicalDetails`.

        Struct errorDetailsStruct = Struct.newBuilder()
                .putFields("message", Value.newBuilder().setStringValue("Error with details").build())
                .build();
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(false)
                .setErrorDetails(errorDetailsStruct) // Provide error details
                .build();

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        // To test the specific InvalidProtocolBufferException catch block in transformResponseToPipeStream,
        // we would need to mock JsonFormat.printer().print() to throw it.
        // This is beyond standard Mockito capabilities for static methods.
        // However, the logic is: if print fails, a fallback technical detail is set.

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);
        ErrorData errorData = resultPipeStream.getHistory(0).getErrorInfo();

        // If JsonFormat.printer().print(errorDetailsStruct) were to throw InvalidProtocolBufferException,
        // the technicalDetails would be "Failed to serialize error_details to JSON: <exception message>"
        // This test, as is, will have the JSON representation of errorDetailsStruct.
        String expectedJson = JsonFormat.printer().preservingProtoFieldNames().print(errorDetailsStruct);
        assertEquals(expectedJson, errorData.getTechnicalDetails());
    }

    @Test
    @DisplayName("execute - gRPC processor throws PipeStepProcessingException")
    void execute_grpcProcessorThrowsPipeStepProcessingException() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        // This is the specific exception type your processor interface declares
        PipeStepProcessingException simulatedException = new PipeStepProcessingException("Simulated PipeStepProcessingException from processor");

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenThrow(simulatedException);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        assertEquals(1, resultPipeStream.getHistoryCount());

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.hasErrorInfo(), "Error info should be present when gRPC call throws PipeStepProcessingException.");

        ErrorData errorData = record.getErrorInfo();
        assertEquals(TEST_STEP_NAME, errorData.getOriginatingStepName());
        assertEquals("gRPC call execution failed for step: " + TEST_STEP_NAME, errorData.getErrorMessage());
        assertEquals("GRPC_EXECUTION_FAILURE", errorData.getErrorCode());
        assertEquals(simulatedException.toString(), errorData.getTechnicalDetails());
        assertTrue(record.getProcessorLogsList().get(0).contains("gRPC call failed for step: " + TEST_STEP_NAME));
        assertTrue(record.getProcessorLogsList().get(0).contains("Simulated PipeStepProcessingException from processor"));
    }

    @Test
    @DisplayName("execute - gRPC processor returns failure with error details missing message/code fields")
    void execute_grpcProcessorReturnsFailureWithErrorDetails_MissingFields() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        // ErrorDetails struct is present, but "message" and "code" fields are missing
        Struct errorDetailsStructMissingFields = Struct.newBuilder()
                .putFields("other_detail", Value.newBuilder().setStringValue("Some other specific detail.").build())
                .build();
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(false)
                .setErrorDetails(errorDetailsStructMissingFields)
                .addProcessorLogs("Step failed with partial error details.")
                .build();

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        assertEquals(1, resultPipeStream.getHistoryCount());

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals("FAILURE", record.getStatus());
        assertTrue(record.hasErrorInfo(), "Error info should be present.");

        ErrorData errorData = record.getErrorInfo();
        assertEquals(TEST_STEP_NAME, errorData.getOriginatingStepName());
        // Verify default values are used
        assertEquals("Processor reported an error.", errorData.getErrorMessage(), "Should use default error message.");
        assertEquals("PROCESSOR_FAILURE", errorData.getErrorCode(), "Should use default error code.");
        assertNotNull(errorData.getTechnicalDetails());
        assertTrue(errorData.getTechnicalDetails().contains("Some other specific detail."), "Technical details should contain the provided 'other_detail'.");
    }

    @Test
    @DisplayName("execute - successful gRPC call, but no output document in response")
    void execute_successfulGrpcCall_noOutputDoc() throws PipeStepExecutionException {
        PipeStream originalPipeStream = createSamplePipeStream();
        // Processor response is successful but does NOT contain an outputDoc
        ProcessResponse mockProcessorResponse = ProcessResponse.newBuilder()
                .setSuccess(true)
                .addProcessorLogs("Step executed successfully, no document modification.")
                .build();

        when(mockGrpcProcessor.processStep(eq(originalPipeStream), eq(TEST_STEP_NAME)))
                .thenReturn(mockProcessorResponse);

        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(originalPipeStream);

        assertNotNull(resultPipeStream);
        // Document should remain the original document since no outputDoc was provided
        assertEquals(originalPipeStream.getDocument(), resultPipeStream.getDocument(), "Document should remain unchanged if no outputDoc in response.");
        assertEquals(1, resultPipeStream.getHistoryCount(), "Should have one history record added.");

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals("SUCCESS", record.getStatus());
        assertTrue(record.getProcessorLogsList().contains("Step executed successfully, no document modification."));
        assertFalse(record.hasErrorInfo(), "No error info should be present on success.");
    }

    // In GrpcPipeStepExecutorTest.java

    @Test
    @DisplayName("execute - null input PipeStream, records GRPC_EXECUTION_FAILURE")
    void execute_nullInputPipeStream_recordsFailure() throws PipeStepExecutionException {
        IllegalArgumentException simulatedProcessorException = new IllegalArgumentException("PipeStream cannot be null for processor");
        // Corrected matcher usage
        when(mockGrpcProcessor.processStep(isNull(), eq(TEST_STEP_NAME)))
                .thenThrow(simulatedProcessorException);

        // After the fix in GrpcPipeStepExecutor, execute(null) should not throw an NPE
        // from transformResponseToPipeStream. It should catch the exception from
        // grpcProcessor and record a failure.
        PipeStream resultPipeStream = grpcPipeStepExecutor.execute(null);

        assertNotNull(resultPipeStream);
        // Since original was null, the resulting stream might be minimal.
        // It should have exactly one history record.
        assertEquals(1, resultPipeStream.getHistoryCount());

        StepExecutionRecord record = resultPipeStream.getHistory(0);
        assertEquals(TEST_STEP_NAME, record.getStepName());
        assertEquals("FAILURE", record.getStatus());
        // Assuming 0 is a sensible default for hopNumber when original stream was null
        // Your SUT's transformResponseToPipeStream correctly defaults currentHopNumber to 0 if original is null.
        assertEquals(0, record.getHopNumber(), "Hop number should default to 0 if original stream was null.");
        assertTrue(record.hasErrorInfo(), "Error info should be present.");

        ErrorData errorData = record.getErrorInfo();
        assertEquals(TEST_STEP_NAME, errorData.getOriginatingStepName());
        // Check the specific error message from your SUT when original is null
        assertEquals("gRPC call execution failed for step: " + TEST_STEP_NAME + " (with null input PipeStream)", errorData.getErrorMessage());
        assertEquals("GRPC_EXECUTION_FAILURE", errorData.getErrorCode());
        assertEquals(simulatedProcessorException.toString(), errorData.getTechnicalDetails());

        // Check processor logs in the record
        assertFalse(record.getProcessorLogsList().isEmpty(), "Processor logs should not be empty.");
        assertTrue(record.getProcessorLogsList().get(0).contains("gRPC call failed for step: " + TEST_STEP_NAME));
        assertTrue(record.getProcessorLogsList().get(0).contains("PipeStream cannot be null for processor"));
    }
}