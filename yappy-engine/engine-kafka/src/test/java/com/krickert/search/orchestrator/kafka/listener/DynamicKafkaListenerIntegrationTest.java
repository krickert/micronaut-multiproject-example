package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.commons.events.PipeStreamProcessingEvent;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
class DynamicKafkaListenerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicKafkaListenerIntegrationTest.class);
    
    private static final String LISTENER_ID = "test-listener-integ";
    private static final String TOPIC = "dynamic-listener-test-topic";
    private static final String GROUP_ID = "dynamic-listener-test-group";
    private static final String PIPELINE_NAME = "test-pipeline";
    private static final String STEP_NAME = "test-step";

    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    ApplicationEventPublisher<PipeStreamProcessingEvent> eventPublisher;
    
    private DynamicKafkaListener listener;
    private AdminClient adminClient;
    private KafkaProducer<UUID, PipeStream> producer;

    @BeforeAll
    void setupAll() {
        // Clear any events from previous test runs
        TestEventListener.clearEvents();
        
        // Create AdminClient
        String bootstrapServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class)
                .orElseThrow(() -> new IllegalStateException("kafka.bootstrap.servers not configured"));
        
        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", bootstrapServers);
        adminClient = AdminClient.create(adminProps);
        
        // Create topic
        NewTopic newTopic = new NewTopic(TOPIC, 1, (short) 1);
        try {
            adminClient.createTopics(Collections.singletonList(newTopic)).all().get();
            LOG.info("Created topic: {}", TOPIC);
        } catch (ExecutionException e) {
            if (e.getCause().getMessage().contains("already exists")) {
                LOG.info("Topic {} already exists", TOPIC);
            } else {
                throw new RuntimeException("Failed to create topic", e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to create topic", e);
        }
        
        // Create producer for testing with proper Apicurio configuration
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer");
        
        // Add all necessary Apicurio configuration
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class)
            .orElse("http://localhost:8081");
        producerProps.put(SerdeConfig.REGISTRY_URL, apicurioUrl);
        producerProps.put(SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
        producerProps.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, 
            "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        
        producer = new KafkaProducer<>(producerProps);
    }
    
    @BeforeEach
    void setUp() {
        TestEventListener.clearEvents();
        
        // Create consumer config
        Map<String, Object> consumerConfig = new HashMap<>();
        consumerConfig.put("bootstrap.servers", 
            applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElseThrow());
        consumerConfig.put("key.deserializer", "org.apache.kafka.common.serialization.UUIDDeserializer");
        consumerConfig.put("value.deserializer", "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        consumerConfig.put("auto.offset.reset", "earliest");
        
        // Use the correct Apicurio SerdeConfig constants
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class)
            .orElse("http://localhost:8081");
        consumerConfig.put(SerdeConfig.REGISTRY_URL, apicurioUrl);
        consumerConfig.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        consumerConfig.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, 
            "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        
        Map<String, String> originalProps = new HashMap<>();
        
        // Create the listener
        listener = new DynamicKafkaListener(
            LISTENER_ID, TOPIC, GROUP_ID, consumerConfig, originalProps,
            PIPELINE_NAME, STEP_NAME, eventPublisher
        );
    }
    
    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.shutdown();
        }
    }
    
    @AfterAll
    void tearDownAll() {
        if (producer != null) {
            producer.close();
        }
        if (adminClient != null) {
            try {
                adminClient.deleteTopics(Collections.singletonList(TOPIC)).all().get();
                LOG.info("Deleted topic: {}", TOPIC);
            } catch (Exception e) {
                LOG.warn("Failed to delete topic: {}", e.getMessage());
            }
            adminClient.close();
        }
    }
    
    @Test
    void testListenerReceivesAndProcessesMessages() throws Exception {
        // Send a test message
        UUID messageId = UUID.randomUUID();
        PipeStream testMessage = PipeStream.newBuilder()
            .setStreamId(messageId.toString())
            .setDocument(PipeDoc.newBuilder()
                .setId("test-doc-123")
                .build())
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(STEP_NAME)
            .putContextParams("messageId", messageId.toString())
            .build();
            
        ProducerRecord<UUID, PipeStream> record = new ProducerRecord<>(TOPIC, messageId, testMessage);
        producer.send(record).get();
        LOG.info("Sent test message with ID: {}", messageId);
        
        // Wait for the event to be captured
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertFalse(TestEventListener.getCapturedEvents().isEmpty(), "Should have captured at least one event");
                
                PipeStreamProcessingEvent event = TestEventListener.getCapturedEvents().get(0);
                PipeStream stream = event.getPipeStream();
                assertNotNull(stream);
                assertEquals("test-doc-123", stream.getDocument().getId());
                assertEquals(messageId.toString(), stream.getContextParamsOrDefault("messageId", ""));
            });
    }
    
    @Test
    void testPauseAndResume() throws Exception {
        // Send initial message
        UUID messageId1 = UUID.randomUUID();
        PipeStream message1 = PipeStream.newBuilder()
            .setStreamId(messageId1.toString())
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-1")
                .build())
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(STEP_NAME)
            .putContextParams("messageId", messageId1.toString())
            .build();
            
        producer.send(new ProducerRecord<>(TOPIC, messageId1, message1)).get();
        
        // Wait for first message
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> TestEventListener.getCapturedEvents().size() >= 1);
            
        int eventsBeforePause = TestEventListener.getCapturedEvents().size();
        
        // Pause the listener
        listener.pause();
        LOG.info("Listener paused");
        Thread.sleep(500); // Give it time to pause
        
        // Send message while paused
        UUID messageId2 = UUID.randomUUID();
        PipeStream message2 = PipeStream.newBuilder()
            .setStreamId(messageId2.toString())
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-2")
                .build())
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(STEP_NAME)
            .putContextParams("messageId", messageId2.toString())
            .build();
            
        producer.send(new ProducerRecord<>(TOPIC, messageId2, message2)).get();
        LOG.info("Sent message while paused: {}", messageId2);
        
        // Wait a bit and verify no new messages processed
        Thread.sleep(2000);
        assertEquals(eventsBeforePause, TestEventListener.getCapturedEvents().size(), 
            "No new events should be processed while paused");
        
        // Resume the listener
        listener.resume();
        LOG.info("Listener resumed");
        
        // Now the paused message should be processed
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> TestEventListener.getCapturedEvents().size() > eventsBeforePause);
            
        // Verify the second message was eventually processed
        boolean foundMessage2 = TestEventListener.getCapturedEvents().stream()
            .anyMatch(e -> {
                PipeStream stream = e.getPipeStream();
                return stream != null && 
                       messageId2.toString().equals(stream.getContextParamsOrDefault("messageId", ""));
            });
        assertTrue(foundMessage2, "Message sent while paused should be processed after resume");
    }
    
    @Test
    void testShutdownStopsProcessing() throws Exception {
        // Send and process initial message
        UUID messageId1 = UUID.randomUUID();
        PipeStream message1 = PipeStream.newBuilder()
            .setStreamId(messageId1.toString())
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-before-shutdown")
                .build())
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(STEP_NAME)
            .putContextParams("messageId", messageId1.toString())
            .build();
            
        producer.send(new ProducerRecord<>(TOPIC, messageId1, message1)).get();
        
        // Wait for first message
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> TestEventListener.getCapturedEvents().size() >= 1);
            
        int eventsBeforeShutdown = TestEventListener.getCapturedEvents().size();
        
        // Shutdown the listener
        listener.shutdown();
        LOG.info("Listener shut down");
        Thread.sleep(1000); // Give it time to fully shutdown
        
        // Send message after shutdown
        UUID messageId2 = UUID.randomUUID();
        PipeStream message2 = PipeStream.newBuilder()
            .setStreamId(messageId2.toString())
            .setDocument(PipeDoc.newBuilder()
                .setId("doc-after-shutdown")
                .build())
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(STEP_NAME)
            .putContextParams("messageId", messageId2.toString())
            .build();
            
        producer.send(new ProducerRecord<>(TOPIC, messageId2, message2)).get();
        LOG.info("Sent message after shutdown: {}", messageId2);
        
        // Wait and verify no new messages processed
        Thread.sleep(3000);
        assertEquals(eventsBeforeShutdown, TestEventListener.getCapturedEvents().size(), 
            "No new events should be processed after shutdown");
        
        // Prevent the @AfterEach from trying to shutdown again
        listener = null;
    }
    
    // Static inner class for event listening (needs to be static for Micronaut to instantiate)
    @Singleton
    static class TestEventListener {
        private static final List<PipeStreamProcessingEvent> capturedEvents = new CopyOnWriteArrayList<>();
        
        @io.micronaut.runtime.event.annotation.EventListener
        void onPipeStreamEvent(PipeStreamProcessingEvent event) {
            capturedEvents.add(event);
            LOG.info("Test listener captured event for pipeline: {}, step: {}, streamId: {}", 
                event.getTargetPipeline(), event.getTargetStep(), 
                event.getPipeStream() != null ? event.getPipeStream().getStreamId() : "null");
        }
        
        static List<PipeStreamProcessingEvent> getCapturedEvents() {
            return capturedEvents;
        }
        
        static void clearEvents() {
            capturedEvents.clear();
        }
    }
}