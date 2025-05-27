package com.krickert.search.pipeline.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine; // Your core engine interface
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
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
    private PipeStreamEngine mockCoreEngine; // Mock the core engine logic

    @Mock
    private StreamObserver<Empty> mockProcessPipeAsyncResponseObserver;

    @Mock
    private StreamObserver<PipeStream> mockTestPipeStreamResponseObserver;

    @Captor
    private ArgumentCaptor<PipeStream> pipeStreamCaptor;

    // Change the captor type to IllegalArgumentException for the specific test case
    @Captor
    private ArgumentCaptor<IllegalArgumentException> illegalArgumentExceptionCaptor;

    // Keep the StatusRuntimeException captor for other tests if they expect it
    @Captor
    private ArgumentCaptor<StatusRuntimeException> statusRuntimeExceptionCaptor;


    private PipeStreamEngineImpl grpcService; // SUT: The gRPC service implementation

    @BeforeEach
    void setUp() {
        grpcService = new PipeStreamEngineImpl(mockCoreEngine);
    }

    private PipeStream createBasicPipeStream(String targetStepName) {
        PipeStream.Builder builder = PipeStream.newBuilder()
                .setStreamId("test-stream-" + UUID.randomUUID().toString().substring(0, 8))
                .setCurrentPipelineName("test-pipeline")
                .setCurrentHopNumber(0);
        // Protobuf builder throws NPE if null is passed to string setters.
        // The service should handle empty string as invalid.
        if (targetStepName != null) {
            builder.setTargetStepName(targetStepName);
        } else {
            // If you truly need to test a builder that hasn't had targetStepName set,
            // you'd build it without calling setTargetStepName, but the default is empty string.
            // For this test's purpose, passing an empty string to createBasicPipeStream is better.
            builder.setTargetStepName(""); // Default to empty if null, or ensure service handles default proto value
        }
        return builder.build();
    }

    @Test
    @DisplayName("processPipeAsync should delegate to coreEngine and complete successfully")
    void processPipeAsync_delegatesToCoreEngine_andCompletes() {
        PipeStream request = createBasicPipeStream("someStep");
        doNothing().when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

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
        // Pass an empty string for targetStepName.
        PipeStream request = createBasicPipeStream("");

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine, never()).processStream(any()); // Core engine should not be called

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        // Capture IllegalArgumentException instead of StatusRuntimeException
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
        doThrow(coreException).when(mockCoreEngine).processStream(any(PipeStream.class));

        grpcService.processPipeAsync(request, mockProcessPipeAsyncResponseObserver);

        verify(mockCoreEngine).processStream(request); // Verify it was called

        verify(mockProcessPipeAsyncResponseObserver, never()).onNext(any());
        verify(mockProcessPipeAsyncResponseObserver, never()).onCompleted();
        verify(mockProcessPipeAsyncResponseObserver).onError(statusRuntimeExceptionCaptor.capture()); // Assuming this part of SUT *does* throw StatusRuntimeException

        StatusRuntimeException exception = statusRuntimeExceptionCaptor.getValue();
        assertEquals(Status.Code.INTERNAL, exception.getStatus().getCode());
        assertNotNull(exception.getStatus().getDescription());
        assertEquals("Failed to process pipe: Core engine failure",exception.getStatus().getDescription());
        // If the service wraps the coreException as a cause for StatusRuntimeException
        // assertEquals(coreException, exception.getStatus().getCause());
    }

    @Test
    @DisplayName("testPipeStream should call onError with UNIMPLEMENTED (as per refactor placeholder)")
    void testPipeStream_callsUnimplemented() {
        PipeStream request = createBasicPipeStream("testStep");

        grpcService.testPipeStream(request, mockTestPipeStreamResponseObserver);

        verify(mockTestPipeStreamResponseObserver, never()).onNext(any());
        verify(mockTestPipeStreamResponseObserver, never()).onCompleted();
        verify(mockTestPipeStreamResponseObserver).onError(statusRuntimeExceptionCaptor.capture()); // Assuming this throws StatusRuntimeException

        StatusRuntimeException exception = statusRuntimeExceptionCaptor.getValue();
        assertEquals(Status.Code.UNIMPLEMENTED, exception.getStatus().getCode());
        assertEquals("testPipeStream needs refactoring", exception.getStatus().getDescription());
    }
}