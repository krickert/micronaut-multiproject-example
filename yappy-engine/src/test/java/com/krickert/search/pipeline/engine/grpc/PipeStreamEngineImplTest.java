package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.model.ErrorData;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.step.PipeStepExecutor;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.step.exception.PipeStepExecutionException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
// import org.mockito.stubbing.Answer; // Not strictly needed for these tests

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipeStreamEngineImplTest {

    @Mock
    private PipeStepExecutorFactory mockExecutorFactory;
    @Mock
    private PipeStreamGrpcForwarder mockGrpcForwarder;
    @Mock
    private KafkaForwarder mockKafkaForwarder;
    @Mock
    private DynamicConfigurationManager mockConfigManager;

    @Mock
    private PipeStepExecutor mockPipeStepExecutor;

    @Mock
    private StreamObserver<Empty> mockResponseObserver; // For processPipeAsync

    // SUT - We will spy on this to verify calls to private error handling methods
    private PipeStreamEngineImpl spiedPipeStreamEngine;

    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamCaptor;
    @Captor
    private ArgumentCaptor<Throwable> throwableCaptor;
    // @Captor private ArgumentCaptor<String> stringCaptor; // Not used in current tests, can be removed if not needed later


    @BeforeEach
    void setUp() {
        PipeStreamEngineImpl realEngine = new PipeStreamEngineImpl(
                mockExecutorFactory,
                mockGrpcForwarder,
                mockKafkaForwarder,
                mockConfigManager
        );
        spiedPipeStreamEngine = spy(realEngine);

        when(mockExecutorFactory.getExecutor(anyString(), anyString())).thenReturn(mockPipeStepExecutor);
        // Use lenient stubbing for private methods that might not always be called
        lenient().doNothing().when(spiedPipeStreamEngine).handleFailedStepExecutionOrRouting(any(), anyString(), any(), anyString());
        lenient().doNothing().when(spiedPipeStreamEngine).handleFailedDispatch(any(), any(), any(), anyString());
    }

    private PipeStream createInitialPipeStream(String pipelineName, String targetStepName) {
        return PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID().toString().substring(0, 8))
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(targetStepName)
                .setCurrentHopNumber(0)
                .build();
    }

    private PipelineStepConfig createStepConfig(String stepName, int maxRetries, long backoffMs, double multiplier, long maxBackoffMs) {
        return PipelineStepConfig.builder()
                .stepName(stepName)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("test-service", null)) // Assuming gRPC for simplicity
                .stepType(StepType.PIPELINE)
                .maxRetries(maxRetries)
                .retryBackoffMs(backoffMs)
                .retryBackoffMultiplier(multiplier)
                .maxRetryBackoffMs(maxBackoffMs)
                .outputs(Collections.emptyMap())
                .build();
    }

    private PipelineConfig createPipelineConfig(String pipelineName, PipelineStepConfig stepConfig) {
        return PipelineConfig.builder()
                .name(pipelineName)
                .pipelineSteps(Map.of(stepConfig.stepName(), stepConfig))
                .build();
    }

    private PipeStream simulateExecutorResponse(PipeStream inputStreamFromEngine, String status, String errorMessage, String errorCode) {
        PipeStream.Builder resultBuilder = inputStreamFromEngine.toBuilder();
        StepExecutionRecord.Builder historyBuilder = StepExecutionRecord.newBuilder()
                .setHopNumber(inputStreamFromEngine.getCurrentHopNumber())
                .setStepName(inputStreamFromEngine.getTargetStepName())
                .setStatus(status)
                .setStartTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis() - 10))
                .setEndTime(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()));

        if (!"SUCCESS".equals(status)) {
            historyBuilder.setErrorInfo(ErrorData.newBuilder()
                    .setErrorMessage(errorMessage != null ? errorMessage : "Executor internal error")
                    .setErrorCode(errorCode != null ? errorCode : "EXECUTOR_FAILURE")
                    .setOriginatingStepName(inputStreamFromEngine.getTargetStepName())
                    .build());
        }
        return resultBuilder.addHistory(historyBuilder.build()).build();
    }

    // --- Test Scenarios ---

    @Test
    @DisplayName("executeStepAndForward - succeeds on first attempt, no retries needed")
    void executeStepAndForward_succeedsOnFirstAttempt() throws Exception {
        String pipelineName = "pSuccessFirst";
        String stepName = "sSuccessFirst";
        PipeStream inputStream = createInitialPipeStream(pipelineName, stepName);
        PipelineStepConfig stepConfig = createStepConfig(stepName, 0, 100L, 2.0, 1000L);
        PipelineConfig pipelineConfig = createPipelineConfig(pipelineName, stepConfig);

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        PipeStream expectedInputToExecutor = inputStream.toBuilder().setCurrentHopNumber(1).build();
        PipeStream successOutputFromExecutor = simulateExecutorResponse(expectedInputToExecutor, "SUCCESS", null, null);
        when(mockPipeStepExecutor.execute(any(PipeStream.class))).thenReturn(successOutputFromExecutor);

        spiedPipeStreamEngine.processPipeAsync(inputStream, mockResponseObserver);

        verify(mockPipeStepExecutor, times(1)).execute(pipeStreamCaptor.capture());
        PipeStream streamSentToExecutor = pipeStreamCaptor.getValue();
        assertEquals(1, streamSentToExecutor.getCurrentHopNumber());
        assertEquals(stepName, streamSentToExecutor.getTargetStepName());

        verify(spiedPipeStreamEngine, never()).handleFailedStepExecutionOrRouting(any(), anyString(), any(), anyString());
        verify(mockResponseObserver).onNext(any(Empty.class)); // From processPipeAsync itself
        verify(mockResponseObserver).onCompleted();
    }

    // In PipeStreamEngineImplTest.java

    @Test
    @DisplayName("executeStepAndForward - fails retryable then succeeds on second attempt")
    void executeStepAndForward_failsRetryableThenSucceeds() throws Exception {
        String pipelineName = "pRetrySuccess";
        String stepName = "sRetrySuccess";
        PipeStream inputStream = createInitialPipeStream(pipelineName, stepName);
        PipelineStepConfig stepConfig = createStepConfig(stepName, 1, 10L, 2.0, 1000L); // 1 retry
        PipelineConfig pipelineConfig = createPipelineConfig(pipelineName, stepConfig);

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Input to the first execute call
        PipeStream firstAttemptInputToExecutor = inputStream.toBuilder().setCurrentHopNumber(1).build();

        // What the engine records after the first *failed* attempt (if it threw PipeStepExecutionException)
        PipeStream stateAfterFirstEngineRecordedFailure = firstAttemptInputToExecutor.toBuilder()
                .addHistory(StepExecutionRecord.newBuilder()
                        .setHopNumber(1) // Hop number of the step
                        .setStepName(stepName)
                        .setStatus("ATTEMPT_FAILURE")
                        .setErrorInfo(ErrorData.newBuilder()
                                .setErrorMessage("Attempt 1 for step sRetrySuccess failed: Simulated retryable gRPC error")
                                .setErrorCode("RETRYABLE_STEP_ERROR"))
                        .build())
                .build();

        // What the executor returns on the *successful second attempt*
        // The input to *this* successful attempt will be `stateAfterFirstEngineRecordedFailure`
        PipeStream successOutputFromExecutorOnSecondTry = simulateExecutorResponse(
                stateAfterFirstEngineRecordedFailure, // This is what the executor gets on 2nd try
                "SUCCESS", null, null
        );

        when(mockPipeStepExecutor.execute(any(PipeStream.class)))
                .thenThrow(new PipeStepExecutionException("Simulated retryable gRPC error", true)) // 1st call
                .thenReturn(successOutputFromExecutorOnSecondTry); // 2nd call

        spiedPipeStreamEngine.processPipeAsync(inputStream, mockResponseObserver);

        verify(mockPipeStepExecutor, times(2)).execute(pipeStreamCaptor.capture());
        List<PipeStream> attempts = pipeStreamCaptor.getAllValues();

        // First attempt (input to executor)
        assertEquals(1, attempts.get(0).getCurrentHopNumber());
        assertEquals(stepName, attempts.get(0).getTargetStepName());
        assertTrue(attempts.get(0).getHistoryList().isEmpty(), "Input to first execute call should have no prior history for this step");

        // Second attempt (input to executor)
        assertEquals(1, attempts.get(1).getCurrentHopNumber(), "Hop number remains same for retry of the same step");
        assertEquals(stepName, attempts.get(1).getTargetStepName());
        assertEquals(1, attempts.get(1).getHistoryCount(), "Input to second execute call should have one history record from the first failed attempt by the engine.");
        StepExecutionRecord firstAttemptFailureRecordByEngine = attempts.get(1).getHistory(0);
        assertEquals("ATTEMPT_FAILURE", firstAttemptFailureRecordByEngine.getStatus());
        assertTrue(firstAttemptFailureRecordByEngine.getErrorInfo().getErrorMessage().contains("Simulated retryable gRPC error"));
        assertEquals("RETRYABLE_STEP_ERROR", firstAttemptFailureRecordByEngine.getErrorInfo().getErrorCode());

        verify(spiedPipeStreamEngine, never()).handleFailedStepExecutionOrRouting(any(), anyString(), any(), anyString());
        verify(mockResponseObserver).onNext(any(Empty.class));
        verify(mockResponseObserver).onCompleted();
    }

    @Test
    @DisplayName("executeStepAndForward - fails non-retryable on first attempt, calls error handler immediately")
    void executeStepAndForward_failsNonRetryableOnFirstAttempt() throws Exception {
        String pipelineName = "pNonRetry";
        String stepName = "sNonRetry";
        PipeStream inputStream = createInitialPipeStream(pipelineName, stepName);
        PipelineStepConfig stepConfig = createStepConfig(stepName, 3, 10L, 2.0, 1000L);
        PipelineConfig pipelineConfig = createPipelineConfig(pipelineName, stepConfig);

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        PipeStepExecutionException nonRetryableEx = new PipeStepExecutionException("Fatal non-retryable error", false);
        when(mockPipeStepExecutor.execute(any(PipeStream.class))).thenThrow(nonRetryableEx);

        spiedPipeStreamEngine.processPipeAsync(inputStream, mockResponseObserver);

        verify(mockPipeStepExecutor, times(1)).execute(any(PipeStream.class));
        verify(spiedPipeStreamEngine, times(1)).handleFailedStepExecutionOrRouting(
                pipeStreamCaptor.capture(),
                eq(stepName),
                throwableCaptor.capture(),
                // MODIFIED EXPECTED ERROR CODE
                eq("STEP_EXECUTION_RETRIES_EXHAUSTED_OR_NON_RETRYABLE")
        );

        assertSame(nonRetryableEx, throwableCaptor.getValue());
        PipeStream streamToHandler = pipeStreamCaptor.getValue();
        assertEquals(1, streamToHandler.getHistoryCount());
        StepExecutionRecord failureRecord = streamToHandler.getHistory(0);
        assertEquals(stepName, failureRecord.getStepName());
        assertEquals("ATTEMPT_FAILURE", failureRecord.getStatus());
        assertTrue(failureRecord.getErrorInfo().getErrorMessage().contains("Fatal non-retryable error"));
        assertEquals("NON_RETRYABLE_STEP_ERROR", failureRecord.getErrorInfo().getErrorCode());

        verify(mockResponseObserver).onNext(any(Empty.class));
        verify(mockResponseObserver).onCompleted();
    }

    @Test
    @DisplayName("executeStepAndForward - fails retryable, exhausts retries, calls error handler")
    void executeStepAndForward_failsRetryableAndExhaustsRetries() throws Exception {
        String pipelineName = "pExhaust";
        String stepName = "sExhaust";
        PipeStream inputStream = createInitialPipeStream(pipelineName, stepName);
        PipelineStepConfig stepConfig = createStepConfig(stepName, 1, 10L, 2.0, 1000L); // 1 retry
        PipelineConfig pipelineConfig = createPipelineConfig(pipelineName, stepConfig);

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        PipeStepExecutionException retryableEx = new PipeStepExecutionException("Exhausting retryable error", true);
        when(mockPipeStepExecutor.execute(any(PipeStream.class)))
                .thenThrow(retryableEx) // 1st attempt
                .thenThrow(retryableEx); // 2nd attempt (retry)

        spiedPipeStreamEngine.processPipeAsync(inputStream, mockResponseObserver);

        verify(mockPipeStepExecutor, times(2)).execute(any(PipeStream.class));
        verify(spiedPipeStreamEngine, times(1)).handleFailedStepExecutionOrRouting(
                pipeStreamCaptor.capture(),
                eq(stepName),
                throwableCaptor.capture(),
                // MODIFIED EXPECTED ERROR CODE
                eq("STEP_EXECUTION_RETRIES_EXHAUSTED_OR_NON_RETRYABLE")
        );

        PipeStream finalFailedStream = pipeStreamCaptor.getValue();
        assertNotNull(finalFailedStream);
        assertEquals(2, finalFailedStream.getHistoryList().stream()
                        .filter(r -> r.getStepName().equals(stepName) && r.getStatus().equals("ATTEMPT_FAILURE"))
                        .count(),
                "Should have two ATTEMPT_FAILURE history records for the step");

        assertSame(retryableEx, throwableCaptor.getValue());
        verify(mockResponseObserver).onNext(any(Empty.class));
        verify(mockResponseObserver).onCompleted();
    }

    @Test
    @DisplayName("executeStepAndForward - executor returns FAILURE status, no engine retries, calls error handler")
    void executeStepAndForward_executorReturnsFailureStatus() throws Exception {
        String pipelineName = "pExecStatusFail";
        String stepName = "sExecStatusFail";
        PipeStream inputStream = createInitialPipeStream(pipelineName, stepName);
        // Configured for retries, but the "FAILURE" status should now prevent engine retries
        PipelineStepConfig stepConfig = createStepConfig(stepName, 2, 10L, 2.0, 1000L);
        PipelineConfig pipelineConfig = createPipelineConfig(pipelineName, stepConfig);

        when(mockConfigManager.getPipelineConfig(pipelineName)).thenReturn(Optional.of(pipelineConfig));

        // Input to the executor for the first (and only) attempt by the engine
        PipeStream inputToExecutor = inputStream.toBuilder().setCurrentHopNumber(1).build();
        PipeStream failureOutputFromExecutor = simulateExecutorResponse(
                inputToExecutor,
                "FAILURE", "Executor logical failure", "EXECUTOR_LOGIC_FAIL"
        );
        when(mockPipeStepExecutor.execute(any(PipeStream.class))).thenReturn(failureOutputFromExecutor);

        spiedPipeStreamEngine.processPipeAsync(inputStream, mockResponseObserver);

        // With the SUT change (break on FAILURE status), executor is called only once
        verify(mockPipeStepExecutor, times(1)).execute(pipeStreamCaptor.capture());

        verify(spiedPipeStreamEngine, times(1)).handleFailedStepExecutionOrRouting(
                pipeStreamCaptor.capture(),
                eq(stepName),
                isA(PipeStepExecutionException.class),
                // MODIFIED EXPECTED ERROR CODE
                eq("STEP_EXECUTION_RETRIES_EXHAUSTED_OR_NON_RETRYABLE")
        );

        PipeStream streamToHandler = pipeStreamCaptor.getAllValues().get(1); // Second capture is for handler
        // streamToHandler should be the stream returned by the executor, which already has its FAILURE record
        assertEquals(failureOutputFromExecutor.getDocument(), streamToHandler.getDocument());
        assertEquals(1, streamToHandler.getHistoryCount(), "Should have one history record from the executor");
        StepExecutionRecord executorFailureRecord = streamToHandler.getHistory(0);
        assertEquals("FAILURE", executorFailureRecord.getStatus());
        assertEquals("Executor logical failure", executorFailureRecord.getErrorInfo().getErrorMessage());

        verify(mockResponseObserver).onNext(any(Empty.class));
        verify(mockResponseObserver).onCompleted();
    }


}