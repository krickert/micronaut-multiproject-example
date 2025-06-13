package com.krickert.search.engine.grpc;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.engine.ProcessResponse;
import com.krickert.search.engine.ProcessStatus;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PipeStreamEngineGrpcService.
 * Tests the gRPC to event publishing bridge functionality.
 */
class PipeStreamEngineGrpcServiceTest {
    
    @Rule
    public final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
    
    ApplicationEventPublisher<PipeStreamProcessingEvent> mockEventPublisher = mock(ApplicationEventPublisher.class);
    
    @Captor
    ArgumentCaptor<PipeStreamProcessingEvent> eventCaptor;
    
    private PipeStreamEngineGrpc.PipeStreamEngineBlockingStub blockingStub;
    private PipeStreamEngineGrpc.PipeStreamEngineStub asyncStub;
    
    
    @BeforeEach
    void setUp() throws IOException {
        MockitoAnnotations.openMocks(this);
        
        // Create service with mocked event publisher
        PipeStreamEngineGrpcService service = new PipeStreamEngineGrpcService(mockEventPublisher);
        
        // Create unique in-process server name
        String serverName = InProcessServerBuilder.generateName();
        
        // Create and start in-process server
        grpcCleanup.register(InProcessServerBuilder
            .forName(serverName)
            .directExecutor()
            .addService(service)
            .build()
            .start());
        
        // Create channel and stubs
        ManagedChannel channel = grpcCleanup.register(
            InProcessChannelBuilder.forName(serverName).directExecutor().build()
        );
        
        blockingStub = PipeStreamEngineGrpc.newBlockingStub(channel);
        asyncStub = PipeStreamEngineGrpc.newStub(channel);
    }
    
    @Test
    void testProcessPipeAsync_ValidRequest() {
        // Given
        PipeStream request = createTestPipeStream("test-stream-1", "test-pipeline", "chunker");
        
        // When
        ProcessResponse response = blockingStub.processPipeAsync(request);
        
        // Then
        assertNotNull(response);
        assertEquals("test-stream-1", response.getStreamId());
        assertEquals(ProcessStatus.ACCEPTED, response.getStatus());
        assertEquals("Request accepted for processing", response.getMessage());
        assertNotNull(response.getRequestId());
        assertTrue(response.getTimestamp() > 0);
        
        // Verify event was published
        verify(mockEventPublisher, times(1)).publishEvent(eventCaptor.capture());
        
        // Get the published event
        PipeStreamProcessingEvent publishedEvent = eventCaptor.getValue();
        assertEquals("test-stream-1", publishedEvent.getPipeStream().getStreamId());
        assertEquals("grpc-gateway", publishedEvent.getSourceModule());
        Map<String, Object> context = publishedEvent.getProcessingContext();
        assertEquals("PipeStreamEngine", context.get("grpc.service"));
        assertEquals("processPipeAsync", context.get("grpc.method"));
        assertNotNull(publishedEvent.getTimestamp());
    }
    
    @Test
    void testProcessPipeAsync_NullRequest() {
        // When/Then
        assertThrows(StatusRuntimeException.class, () -> {
            blockingStub.processPipeAsync(null);
        });
    }
    
    @Test
    void testProcessPipeAsync_NoDocument() {
        // Given - PipeStream without document
        PipeStream request = PipeStream.newBuilder()
            .setStreamId("test-stream-1")
            .setCurrentPipelineName("test-pipeline")
            .build();
        
        // When/Then
        StatusRuntimeException exception = assertThrows(StatusRuntimeException.class, () -> {
            blockingStub.processPipeAsync(request);
        });
        
        assertTrue(exception.getMessage().contains("must contain a document"));
    }
    
    @Test
    void testProcessPipeStream_MultipleMessages() throws InterruptedException {
        // Given
        CountDownLatch responseLatch = new CountDownLatch(3);
        List<ProcessResponse> responses = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        
        StreamObserver<ProcessResponse> responseObserver = new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse response) {
                responses.add(response);
                responseLatch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                errors.add(t);
            }
            
            @Override
            public void onCompleted() {
                // Stream completed
            }
        };
        
        StreamObserver<PipeStream> requestObserver = asyncStub.processPipeStream(responseObserver);
        
        // When - Send multiple messages
        for (int i = 1; i <= 3; i++) {
            PipeStream request = createTestPipeStream("stream-" + i, "pipeline-" + i, "step-" + i);
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        
        // Then
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertEquals(3, responses.size());
        assertTrue(errors.isEmpty());
        
        // Verify all responses are accepted
        for (int i = 0; i < 3; i++) {
            ProcessResponse response = responses.get(i);
            assertEquals("stream-" + (i + 1), response.getStreamId());
            assertEquals(ProcessStatus.ACCEPTED, response.getStatus());
            assertTrue(response.getMessage().contains("Stream message " + (i + 1) + " accepted"));
        }
        
        // Verify events were published
        verify(mockEventPublisher, times(3)).publishEvent(any(PipeStreamProcessingEvent.class));
    }
    
    @Test
    void testProcessPipeStream_ErrorHandling() throws InterruptedException {
        // Given - Mock publisher to throw exception
        doThrow(new RuntimeException("Publishing failed")).when(mockEventPublisher).publishEvent(any());
        
        CountDownLatch responseLatch = new CountDownLatch(1);
        List<ProcessResponse> responses = new ArrayList<>();
        
        StreamObserver<ProcessResponse> responseObserver = new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse response) {
                responses.add(response);
                responseLatch.countDown();
            }
            
            @Override
            public void onError(Throwable t) {
                // Should not be called - errors are sent as responses
            }
            
            @Override
            public void onCompleted() {
                // Stream completed
            }
        };
        
        StreamObserver<PipeStream> requestObserver = asyncStub.processPipeStream(responseObserver);
        
        // When
        PipeStream request = createTestPipeStream("error-stream", "error-pipeline", "error-step");
        requestObserver.onNext(request);
        requestObserver.onCompleted();
        
        // Then
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, responses.size());
        
        ProcessResponse response = responses.get(0);
        assertEquals("error-stream", response.getStreamId());
        assertEquals(ProcessStatus.ERROR, response.getStatus());
        assertTrue(response.getMessage().contains("Error processing message"));
    }
    
    private PipeStream createTestPipeStream(String streamId, String pipeline, String targetStep) {
        PipeDoc document = PipeDoc.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setBody("Test content for " + streamId)
            .setTitle("Test Document")
            .build();
        
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setCurrentPipelineName(pipeline)
            .setTargetStepName(targetStep)
            .setDocument(document)
            .putContextParams("test", "true")
            .build();
    }
    
    /**
     * Test event listener to verify event publishing in integration scenarios.
     */
    static class TestEventListener implements ApplicationEventListener<PipeStreamProcessingEvent> {
        private final List<PipeStreamProcessingEvent> receivedEvents = new ArrayList<>();
        private final CountDownLatch latch;
        
        TestEventListener(int expectedEvents) {
            this.latch = new CountDownLatch(expectedEvents);
        }
        
        @Override
        public void onApplicationEvent(PipeStreamProcessingEvent event) {
            receivedEvents.add(event);
            latch.countDown();
        }
        
        boolean awaitEvents(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
        
        List<PipeStreamProcessingEvent> getReceivedEvents() {
            return receivedEvents;
        }
    }
}