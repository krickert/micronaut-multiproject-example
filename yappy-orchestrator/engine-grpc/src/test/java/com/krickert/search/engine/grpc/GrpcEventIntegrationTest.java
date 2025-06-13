package com.krickert.search.engine.grpc;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.engine.PipeStreamEngineGrpc;
import com.krickert.search.engine.ProcessResponse;
import com.krickert.search.engine.ProcessStatus;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import io.micronaut.context.event.ApplicationEventListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcEmbeddedServer;
import jakarta.annotation.PostConstruct;
import io.micronaut.grpc.server.GrpcServerConfiguration;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the complete gRPC to event flow.
 * This test verifies that:
 * 1. gRPC server starts correctly
 * 2. Requests are converted to events
 * 3. Events are published and received by listeners
 * 4. Response is sent back to client
 */
@MicronautTest
class GrpcEventIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(GrpcEventIntegrationTest.class);
    
    @Inject
    GrpcEmbeddedServer grpcServer;
    
    ManagedChannel channel;
    PipeStreamEngineGrpc.PipeStreamEngineBlockingStub blockingStub;
    
    @Inject
    TestEventCollector eventCollector;
    
    @PostConstruct
    void setupStub() {
        if (grpcServer != null) {
            // Create channel to the embedded server
            channel = ManagedChannelBuilder.forAddress("localhost", grpcServer.getPort())
                .usePlaintext()
                .build();
            blockingStub = PipeStreamEngineGrpc.newBlockingStub(channel);
            logger.info("Created gRPC channel to embedded server on port {}", grpcServer.getPort());
        }
    }
    
    @PreDestroy
    void cleanupChannel() {
        if (channel != null && !channel.isShutdown()) {
            channel.shutdown();
        }
    }
    
    @Test
    void testFullGrpcToEventFlow() throws InterruptedException {
        logger.info("Testing full gRPC to event flow");
        
        // Given
        String streamId = "integration-test-" + UUID.randomUUID().toString();
        PipeStream request = createTestPipeStream(streamId, "integration-pipeline", "chunker");
        
        // Reset event collector
        eventCollector.reset(1); // Expecting 1 event
        
        // When
        ProcessResponse response = blockingStub.processPipeAsync(request);
        
        // Then - Verify gRPC response
        assertNotNull(response);
        assertEquals(streamId, response.getStreamId());
        assertEquals(ProcessStatus.ACCEPTED, response.getStatus());
        assertEquals("Request accepted for processing", response.getMessage());
        assertNotNull(response.getRequestId());
        assertTrue(response.getTimestamp() > 0);
        
        // Wait for events to be published and received
        boolean eventsReceived = eventCollector.await(5, TimeUnit.SECONDS);
        assertTrue(eventsReceived, "Events should be received within timeout");
        
        // Verify events
        List<PipeStreamProcessingEvent> events = eventCollector.getEvents();
        assertEquals(1, events.size(), "Should receive 1 event");
        
        // Check the event
        PipeStreamProcessingEvent finalEvent = events.get(0);
        assertEquals(streamId, finalEvent.getPipeStream().getStreamId());
        assertEquals("grpc-gateway", finalEvent.getSourceModule());
        Map<String, Object> context = finalEvent.getProcessingContext();
        assertEquals("PipeStreamEngine", context.get("grpc.service"));
        assertEquals("processPipeAsync", context.get("grpc.method"));
        assertNotNull(finalEvent.getTimestamp());
        
        // Verify request ID was added to context
        assertTrue(finalEvent.getPipeStream().containsContextParams("requestId"));
        assertEquals(response.getRequestId(), finalEvent.getPipeStream().getContextParamsOrThrow("requestId"));
        
        logger.info("Full gRPC to event flow test completed successfully");
    }
    
    @Test
    void testMultipleRequests() throws InterruptedException {
        logger.info("Testing multiple gRPC requests");
        
        // Given
        int requestCount = 3;
        eventCollector.reset(requestCount); // Each request generates 1 event
        
        List<ProcessResponse> responses = new ArrayList<>();
        
        // When - Send multiple requests
        for (int i = 0; i < requestCount; i++) {
            String streamId = "multi-test-" + i + "-" + UUID.randomUUID().toString();
            PipeStream request = createTestPipeStream(streamId, "multi-pipeline-" + i, "step-" + i);
            
            ProcessResponse response = blockingStub.processPipeAsync(request);
            responses.add(response);
        }
        
        // Then - Verify all responses
        assertEquals(requestCount, responses.size());
        for (int i = 0; i < requestCount; i++) {
            ProcessResponse response = responses.get(i);
            assertTrue(response.getStreamId().startsWith("multi-test-" + i));
            assertEquals(ProcessStatus.ACCEPTED, response.getStatus());
        }
        
        // Wait for all events
        boolean allEventsReceived = eventCollector.await(10, TimeUnit.SECONDS);
        assertTrue(allEventsReceived, "All events should be received within timeout");
        
        List<PipeStreamProcessingEvent> events = eventCollector.getEvents();
        assertEquals(requestCount, events.size(), "Should receive " + requestCount + " events");
        
        // Verify each event
        for (int i = 0; i < events.size(); i++) {
            PipeStreamProcessingEvent event = events.get(i);
            assertTrue(event.getPipeStream().getStreamId().startsWith("multi-test-"));
            assertEquals("grpc-gateway", event.getSourceModule());
            Map<String, Object> eventContext = event.getProcessingContext();
            assertEquals("PipeStreamEngine", eventContext.get("grpc.service"));
            assertEquals("processPipeAsync", eventContext.get("grpc.method"));
            assertTrue(event.getPipeStream().containsContextParams("requestId"));
        }
        
        logger.info("Multiple gRPC requests test completed successfully");
    }
    
    private PipeStream createTestPipeStream(String streamId, String pipeline, String targetStep) {
        PipeDoc document = PipeDoc.newBuilder()
            .setId(UUID.randomUUID().toString())
            .setBody("Integration test content for " + streamId)
            .setTitle("Integration Test Document")
            .build();
        
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setCurrentPipelineName(pipeline)
            .setTargetStepName(targetStep)
            .setDocument(document)
            .putContextParams("test-type", "integration")
            .putContextParams("test-class", "GrpcEventIntegrationTest")
            .build();
    }
    
    /**
     * Test event collector that gathers published events for verification.
     */
    @Singleton
    static class TestEventCollector implements ApplicationEventListener<PipeStreamProcessingEvent> {
        
        private static final Logger logger = LoggerFactory.getLogger(TestEventCollector.class);
        
        private final List<PipeStreamProcessingEvent> events = new ArrayList<>();
        private CountDownLatch latch;
        
        @Override
        public void onApplicationEvent(PipeStreamProcessingEvent event) {
            synchronized (events) {
                events.add(event);
                logger.debug("Event collected: streamId={}, source={}, method={}, total={}", 
                    event.getPipeStream().getStreamId(), 
                    event.getSourceModule(), 
                    event.getProcessingContext().get("grpc.method"),
                    events.size());
                
                if (latch != null) {
                    latch.countDown();
                }
            }
        }
        
        void reset(int expectedEvents) {
            synchronized (events) {
                events.clear();
                latch = new CountDownLatch(expectedEvents);
                logger.debug("Event collector reset, expecting {} events", expectedEvents);
            }
        }
        
        boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            return latch != null && latch.await(timeout, unit);
        }
        
        List<PipeStreamProcessingEvent> getEvents() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
        
        @PreDestroy
        void cleanup() {
            synchronized (events) {
                events.clear();
                if (latch != null) {
                    // Release any waiting threads
                    while (latch.getCount() > 0) {
                        latch.countDown();
                    }
                }
            }
        }
    }
}