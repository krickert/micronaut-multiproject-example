package com.krickert.search.pipeline.engine.grpc;// In PipeStreamEngineImplTest.java

import com.google.protobuf.Empty;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine; // Your core engine interface
import com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Provider; // 👈 Make sure this import is present
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PipeStreamEngineImplTest {

    @Mock
    private PipeStreamEngine mockCoreEngine; // This remains your mock for the core logic

    @Mock
    private StreamObserver<Empty> mockProcessPipeAsyncResponseObserver;
    @Mock
    private StreamObserver<PipeStream> mockTestPipeStreamResponseObserver;

    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamCaptor;
    @Captor
    private ArgumentCaptor<IllegalArgumentException> illegalArgumentExceptionCaptor;
    @Captor
    private ArgumentCaptor<StatusRuntimeException> statusRuntimeExceptionCaptor;

    private PipeStreamEngineImpl grpcService; // SUT

    @BeforeEach
    void setUp() {
        // Create a Provider that returns your mockCoreEngine
        // The lambda () -> mockCoreEngine is a concise way to implement Provider.get()
        Provider<PipeStreamEngine> mockCoreEngineProvider = () -> mockCoreEngine;

        // Instantiate the SUT (grpcService) with the Provider
        grpcService = new PipeStreamEngineImpl(mockCoreEngineProvider);
    }

    // ... createBasicPipeStream method remains the same ...
    private PipeStream createBasicPipeStream(String targetStepName) {
        PipeStream.Builder builder = PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID().toString().substring(0, 8))
                .setCurrentPipelineName("test-pipeline")
                .setCurrentHopNumber(0);
        if (targetStepName != null) {
            builder.setTargetStepName(targetStepName);
        } else {
            builder.setTargetStepName("");
        }
        return builder.build();
    }


    @Test
    @DisplayName("processPipeAsync should delegate to coreEngine and complete successfully")
    void processPipeAsync_delegatesToCoreEngine_andCompletes() {
        PipeStream request = createBasicPipeStream("someStep");
        // mockCoreEngine is what coreEngineProvider.get() will return in the SUT
        doNothing().when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        // Verify interactions on mockCoreEngine
        verify(mockCoreEngine).processStream(pipeStreamCaptor.capture());
        assertEquals(request.getStreamId(), pipeStreamCaptor.getValue().getStreamId());
        assertEquals("someStep", pipeStreamCaptor.getValue().getTargetStepName());

        verify(mockProcessPipeAsyncResponseObserver).onNext(Empty.getDefaultInstance());
        verify(mockProcessPipeAsyncResponseObserver).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver, never()).onError(any());
    }

    @Test
    @DisplayName("processPipeAsync should handle empty targetStepName and call onError")
    void processPipeAsync_emptyTargetStep_callsOnError() {
        PipeStream request = createBasicPipeStream("");

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine, never()).processStream(any());

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver).onError(illegalArgumentExceptionCaptor.capture());

        IllegalArgumentException exception = illegalArgumentExceptionCaptor.getValue();
        assertNotNull(exception, "Exception should not be null");
        assertEquals("Target step name must be set in the request", exception.getMessage());
    }

    @Test
    @DisplayName("processPipeAsync should handle exception from coreEngine and call onError")
    void processPipeAsync_coreEngineThrowsException_callsOnError() {
        PipeStream request = createBasicPipeStream("someStep");
        RuntimeException coreException = new RuntimeException("Core engine failure");
        // mockCoreEngine is what coreEngineProvider.get() will return in the SUT
        doThrow(coreException).when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine).processStream(request);

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver).onError(statusRuntimeExceptionCaptor.capture());

        StatusRuntimeException exception = statusRuntimeExceptionCaptor.getValue();
        assertEquals(Status.Code.INTERNAL, exception.getStatus().getCode());
        assertNotNull(exception.getStatus().getDescription());
        assertEquals("Failed to process pipe: Core engine failure",exception.getStatus().getDescription());
        // assertEquals(coreException, exception.getStatus().getCause()); // This assertion is good too
    }

    @Test
    @DisplayName("testPipeStream should call onError with UNIMPLEMENTED (as per refactor placeholder)")
    void testPipeStream_callsUnimplemented() {
        PipeStream request = createBasicPipeStream("testStep");

        grpcService.testPipeStream(request, mockTestPipeStreamResponseObserver);

        verify(mockTestPipeStreamResponseObserver, never()).onNext(any());
        verify(mockTestPipeStreamResponseObserver, never()).onCompleted();
        verify(mockTestPipeStreamResponseObserver).onError(statusRuntimeExceptionCaptor.capture());

        StatusRuntimeException exception = statusRuntimeExceptionCaptor.getValue();
        assertEquals(Status.Code.UNIMPLEMENTED, exception.getStatus().getCode());
        assertEquals("testPipeStream needs refactoring", exception.getStatus().getDescription());
    }
}