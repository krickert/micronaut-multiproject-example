package com.krickert.search.engine.integration;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test demonstrating the event-driven architecture.
 * This test shows how kafka-service and engine-core communicate through events
 * without direct dependencies or complex test resource setup.
 */
@MicronautTest
class EventDrivenIntegrationTest {
    
    private static final Logger logger = LoggerFactory.getLogger(EventDrivenIntegrationTest.class);
    
    @Inject
    ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher;
    
    @Inject
    TestEventCapture eventCapture;
    
    @BeforeEach
    void setup() {
        eventCapture.clear();
    }
    
    @Test
    void testEventDrivenProcessing() throws InterruptedException {
        // Given - a PipeStream document
        String streamId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-1")
                .setTitle("Test Document")
                .setBody("This is a test document for event-driven processing")
                .build();
                
        PipeStream pipeStream = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(doc)
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("chunker")
                .build();
        
        // When - we publish an event (simulating kafka-service behavior)
        PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromKafka(
                pipeStream,
                "test-topic",
                0,
                100L,
                "test-consumer-group"
        );
        
        logger.info("Publishing PipeStreamProcessingEvent for stream: {}", streamId);
        eventPublisher.publishEvent(event);
        
        // Then - the event should be received and processed
        boolean eventReceived = eventCapture.awaitEvent(5, TimeUnit.SECONDS);
        assertThat(eventReceived)
                .as("Event should be received within 5 seconds")
                .isTrue();
        
        assertThat(eventCapture.getReceivedEvents())
                .hasSize(1)
                .first()
                .satisfies(receivedEvent -> {
                    assertThat(receivedEvent.getPipeStream().getStreamId()).isEqualTo(streamId);
                    assertThat(receivedEvent.getPipeStream().getCurrentPipelineName()).isEqualTo("test-pipeline");
                    assertThat(receivedEvent.getPipeStream().getTargetStepName()).isEqualTo("chunker");
                    assertThat(receivedEvent.getSourceModule()).isEqualTo("kafka-listener");
                });
        
        logger.info("Event-driven test completed successfully!");
    }
    
    @Test
    void testMultipleEventProcessing() throws InterruptedException {
        // Given - multiple PipeStream documents
        int eventCount = 10;
        eventCapture.setExpectedCount(eventCount);
        
        // When - we publish multiple events
        for (int i = 0; i < eventCount; i++) {
            PipeStream pipeStream = PipeStream.newBuilder()
                    .setStreamId(UUID.randomUUID().toString())
                    .setDocument(PipeDoc.newBuilder()
                            .setId("doc-" + i)
                            .setTitle("Document " + i)
                            .setBody("Body content " + i)
                            .build())
                    .setCurrentPipelineName("pipeline-" + (i % 3))
                    .setTargetStepName("step-" + (i % 2))
                    .build();
            
            PipeStreamProcessingEvent event = PipeStreamProcessingEvent.fromKafka(
                    pipeStream,
                    "test-topic",
                    i % 3,  // Different partitions
                    i * 100L,
                    "test-group"
            );
            
            eventPublisher.publishEvent(event);
        }
        
        // Then - all events should be received
        boolean allEventsReceived = eventCapture.awaitEvents(10, TimeUnit.SECONDS);
        assertThat(allEventsReceived)
                .as("All events should be received within 10 seconds")
                .isTrue();
        
        assertThat(eventCapture.getReceivedEvents()).hasSize(eventCount);
        
        // Verify event metadata
        eventCapture.getReceivedEvents().forEach(event -> {
            assertThat(event.getProcessingContext())
                    .containsKeys("kafka.topic", "kafka.partition", "kafka.offset", "kafka.consumer.group");
        });
    }
    
    /**
     * Test event capture utility for verifying events in tests.
     */
    @Singleton
    static class TestEventCapture implements ApplicationEventListener<PipeStreamProcessingEvent> {
        
        private final CopyOnWriteArrayList<PipeStreamProcessingEvent> receivedEvents = new CopyOnWriteArrayList<>();
        private CountDownLatch latch = new CountDownLatch(1);
        private int expectedCount = 1;
        
        @Override
        public void onApplicationEvent(PipeStreamProcessingEvent event) {
            logger.debug("TestEventCapture received event for stream: {}", event.getPipeStream().getStreamId());
            receivedEvents.add(event);
            
            if (receivedEvents.size() >= expectedCount) {
                latch.countDown();
            }
        }
        
        public boolean awaitEvent(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
        
        public boolean awaitEvents(long timeout, TimeUnit unit) throws InterruptedException {
            return latch.await(timeout, unit);
        }
        
        public void setExpectedCount(int count) {
            this.expectedCount = count;
            this.latch = new CountDownLatch(1);
        }
        
        public void clear() {
            receivedEvents.clear();
            latch = new CountDownLatch(1);
            expectedCount = 1;
        }
        
        public CopyOnWriteArrayList<PipeStreamProcessingEvent> getReceivedEvents() {
            return receivedEvents;
        }
    }
}