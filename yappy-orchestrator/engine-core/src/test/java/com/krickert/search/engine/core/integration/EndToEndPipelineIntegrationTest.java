package com.krickert.search.engine.core.integration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.core.PipelineEngine;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.orchestrator.kafka.producer.KafkaForwarder;
import com.krickert.search.orchestrator.kafka.listener.KafkaListenerManager;
import com.krickert.search.orchestrator.kafka.listener.KafkaListenerPool;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.apache.kafka.common.serialization.UUIDSerializer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive end-to-end integration test that demonstrates:
 * 1. Full pipeline configuration setup in Consul
 * 2. Kafka listener creation via configuration events
 * 3. PipeStream message processing through the pipeline engine
 * 4. Message routing and forwarding to Kafka topics
 * 5. Event-driven architecture working end-to-end
 * 
 * This test serves as both validation and documentation of the complete system integration.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "engine.event-driven.enabled", value = "true")
class EndToEndPipelineIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(EndToEndPipelineIntegrationTest.class);
    
    private String TEST_CLUSTER_NAME;
    private static final String PIPELINE_NAME = "end-to-end-test-pipeline";
    private static final String INPUT_TOPIC = "raw-documents";
    private static final String OUTPUT_TOPIC = "processed-documents";
    private static final String KAFKA_STEP_NAME = "kafka-ingestion";
    private static final String PROCESSING_STEP_NAME = "document-processing";
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    ConsulBusinessOperationsService consulOps;
    
    @Inject
    PipelineEngine pipelineEngine;
    
    @Inject
    KafkaListenerManager kafkaListenerManager;
    
    @Inject
    KafkaListenerPool listenerPool;
    
    @Inject
    KafkaForwarder kafkaForwarder;
    
    @Inject
    AdminClient adminClient;
    
    private KafkaProducer<UUID, PipeStream> producer;
    private KafkaConsumer<UUID, PipeStream> outputConsumer;
    private final List<PipeStream> receivedMessages = new CopyOnWriteArrayList<>();
    
    @BeforeAll
    void setupAll() throws ExecutionException, InterruptedException {
        // Get cluster name from configuration
        TEST_CLUSTER_NAME = applicationContext.getProperty("app.config.cluster-name", String.class)
                .orElseThrow(() -> new IllegalStateException("app.config.cluster-name not configured"));
        LOG.info("Using cluster name: {}", TEST_CLUSTER_NAME);
        
        // Create Kafka topics
        createKafkaTopics();
        
        // Setup Kafka producer and consumer
        setupKafkaClients();
        
        LOG.info("End-to-end integration test setup completed");
    }
    
    @BeforeEach
    void setUp() {
        // Clear any existing configuration and captured messages
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        receivedMessages.clear();
        
        // Wait for cleanup
        await().atMost(Duration.ofSeconds(5))
            .until(() -> listenerPool.getListenerCount() == 0);
    }
    
    @AfterEach
    void tearDown() {
        // Clean up configuration and listeners
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        listenerPool.shutdownAllListeners();
    }
    
    @AfterAll
    void tearDownAll() {
        if (producer != null) {
            producer.close();
        }
        if (outputConsumer != null) {
            outputConsumer.close();
        }
        
        // Clean up topics
        try {
            adminClient.deleteTopics(List.of(INPUT_TOPIC, OUTPUT_TOPIC)).all().get();
            LOG.info("Cleaned up test topics");
        } catch (Exception e) {
            LOG.warn("Failed to clean up topics: {}", e.getMessage());
        }
    }
    
    @Test
    @DisplayName("Complete end-to-end pipeline execution with Kafka integration")
    void testCompleteEndToEndPipelineExecution() {
        // 1. Create and store comprehensive pipeline configuration
        PipelineClusterConfig config = createEndToEndPipelineConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        LOG.info("Stored pipeline configuration in Consul");
        
        // 2. Wait for Kafka listeners to be created automatically via configuration events
        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertEquals(1, listenerPool.getListenerCount(), 
                    "Kafka listener should be created from configuration");
                LOG.debug("Kafka listener successfully created");
            });
        
        // 3. Start consuming from output topic to verify pipeline completion
        startOutputTopicConsumer();
        
        // 4. Create and send test PipeStream message
        PipeStream testMessage = createTestPipeStream();
        LOG.info("Sending test message: streamId={}, pipeline={}", 
            testMessage.getStreamId(), testMessage.getCurrentPipelineName());
        
        // 5. Send message via Kafka producer (simulating external system)
        try {
            ProducerRecord<UUID, PipeStream> record = new ProducerRecord<>(
                INPUT_TOPIC, 
                UUID.fromString(testMessage.getStreamId()), 
                testMessage
            );
            producer.send(record).get(10, TimeUnit.SECONDS);
            LOG.info("Test message sent to input topic: {}", INPUT_TOPIC);
        } catch (Exception e) {
            fail("Failed to send test message: " + e.getMessage());
        }
        
        // 6. Verify message processing through the pipeline
        // The message should be:
        // a) Consumed by our Kafka listener
        // b) Processed by the pipeline engine via event-driven architecture
        // c) Routed to the output topic via KafkaMessageForwarder
        
        await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(1000))
            .untilAsserted(() -> {
                assertFalse(receivedMessages.isEmpty(), 
                    "Should have received at least one processed message on output topic");
                
                PipeStream receivedMessage = receivedMessages.get(0);
                assertEquals(testMessage.getStreamId(), receivedMessage.getStreamId(),
                    "Received message should have same stream ID");
                assertEquals(testMessage.getCurrentPipelineName(), receivedMessage.getCurrentPipelineName(),
                    "Received message should have same pipeline name");
                
                LOG.info("✅ End-to-end pipeline test successful! Message processed from {} to {}", 
                    INPUT_TOPIC, OUTPUT_TOPIC);
            });
        
        // 7. Verify pipeline engine integration
        verifyPipelineEngineIntegration(testMessage);
        
        // 8. Verify event-driven architecture
        verifyEventDrivenArchitecture();
        
        LOG.info("🎉 Complete end-to-end pipeline integration test passed!");
    }
    
    @Test
    @DisplayName("Pipeline configuration validation and listener lifecycle")
    void testPipelineConfigurationAndListenerLifecycle() {
        // 1. Create pipeline with multiple Kafka inputs/outputs
        PipelineClusterConfig config = createMultiStepPipelineConfig();
        
        // 2. Store configuration and verify listener creation
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() >= 1);
            
        int initialListenerCount = listenerPool.getListenerCount();
        LOG.info("Created {} listeners from configuration", initialListenerCount);
        
        // 3. Update configuration (add/remove steps)
        PipelineClusterConfig updatedConfig = createUpdatedPipelineConfig(config);
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, updatedConfig).block();
        
        // 4. Verify listener pool updates automatically
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() != initialListenerCount);
            
        LOG.info("Listener count updated to {} after configuration change", 
            listenerPool.getListenerCount());
        
        // 5. Delete configuration and verify cleanup
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 0);
            
        LOG.info("✅ All listeners cleaned up after configuration deletion");
    }
    
    @Test
    @DisplayName("Message routing and transport selection")
    void testMessageRoutingAndTransportSelection() {
        // 1. Create pipeline with mixed transport types (Kafka + gRPC)
        PipelineClusterConfig config = createMixedTransportPipelineConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        // 2. Wait for setup
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() > 0);
        
        // 3. Create test message and process through pipeline engine
        PipeStream testMessage = createTestPipeStream();
        
        // 4. Verify the pipeline engine can route messages correctly
        StepVerifier.create(pipelineEngine.processMessage(testMessage))
            .verifyComplete();
            
        LOG.info("✅ Pipeline engine successfully processed message with mixed transports");
    }
    
    @Test  
    @DisplayName("Error handling and recovery scenarios")
    void testErrorHandlingAndRecovery() {
        // 1. Create pipeline configuration with error handling
        PipelineClusterConfig config = createErrorHandlingPipelineConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() > 0);
        
        // 2. Send message that would trigger error path (invalid target step)
        PipeStream errorMessage = createTestPipeStream().toBuilder()
            .setTargetStepName("non-existent-step")
            .build();
        
        // 3. Verify pipeline handles error gracefully
        StepVerifier.create(pipelineEngine.processMessage(errorMessage))
            .verifyComplete(); // Should complete without throwing
            
        LOG.info("✅ Pipeline engine handled error scenario gracefully");
    }
    
    // Helper methods
    
    private void createKafkaTopics() throws ExecutionException, InterruptedException {
        List<NewTopic> topics = List.of(
            new NewTopic(INPUT_TOPIC, 1, (short) 1),
            new NewTopic(OUTPUT_TOPIC, 1, (short) 1),
            new NewTopic("chunked-documents", 1, (short) 1),
            new NewTopic("error-topic", 1, (short) 1)
        );
        
        try {
            adminClient.createTopics(topics).all().get();
            LOG.info("Created test topics: {}", topics.stream().map(NewTopic::name).toList());
        } catch (ExecutionException e) {
            if (e.getCause().getMessage().contains("already exists")) {
                LOG.info("Test topics already exist");
            } else {
                throw e;
            }
        }
    }
    
    private void setupKafkaClients() {
        String bootstrapServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class)
                .orElseThrow(() -> new IllegalStateException("kafka.bootstrap.servers not configured"));
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class)
                .orElse("http://localhost:8081");
        
        // Producer configuration
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUIDSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer");
        producerProps.put("apicurio.registry.url", apicurioUrl);
        producerProps.put("apicurio.registry.auto-register", "true");
        producerProps.put("apicurio.registry.artifact-resolver-strategy", 
            "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        producer = new KafkaProducer<>(producerProps);
        
        // Consumer configuration
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "end-to-end-test-consumer");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
            "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put("apicurio.registry.url", apicurioUrl);
        consumerProps.put("apicurio.registry.deserializer.specific.value.return.class", 
            PipeStream.class.getName());
        consumerProps.put("apicurio.registry.artifact-resolver-strategy", 
            "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        outputConsumer = new KafkaConsumer<>(consumerProps);
    }
    
    private void startOutputTopicConsumer() {
        outputConsumer.subscribe(List.of(OUTPUT_TOPIC));
        
        // Start background consumer
        CompletableFuture.runAsync(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ConsumerRecords<UUID, PipeStream> records = outputConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        LOG.info("Received message on output topic: streamId={}, value={}", 
                            record.key(), record.value().getStreamId());
                        receivedMessages.add(record.value());
                    }
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    LOG.error("Error in output consumer", e);
                }
            }
        });
    }
    
    private PipeStream createTestPipeStream() {
        String streamId = UUID.randomUUID().toString();
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(KAFKA_STEP_NAME)
            .setCurrentHopNumber(1)
            .setDocument(PipeDoc.newBuilder()
                .setId("test-doc-" + streamId)
                .setTitle("End-to-End Test Document")
                .setBody("This is a test document for end-to-end pipeline integration testing.")
                .build())
            .putContextParams("test.source", "end-to-end-integration-test")
            .putContextParams("test.timestamp", String.valueOf(System.currentTimeMillis()))
            .build();
    }
    
    private PipelineClusterConfig createEndToEndPipelineConfig() {
        // Create processor info for the step (required)
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
            .grpcServiceName("yappy-echo") // Use echo service for simplicity
            .build();

        // Kafka ingestion step
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
            .listenTopics(List.of(INPUT_TOPIC))
            .consumerGroupId(TEST_CLUSTER_NAME + "-end-to-end-group")
            .kafkaConsumerProperties(Map.of(
                "auto.offset.reset", "earliest",
                "max.poll.records", "10"
            ))
            .build();
            
        PipelineStepConfig kafkaStep = PipelineStepConfig.builder()
            .stepName(KAFKA_STEP_NAME)
            .stepType(StepType.INITIAL_PIPELINE)
            .description("Kafka ingestion step for end-to-end test")
            .kafkaInputs(List.of(kafkaInput))
            .processorInfo(processorInfo)
            .outputs(Map.of(
                "success", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName(PROCESSING_STEP_NAME)
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic(OUTPUT_TOPIC)
                        .kafkaProducerProperties(Map.of("acks", "all"))
                        .build())
                    .build()
            ))
            .build();
            
        // Simple processing step (sink)
        PipelineStepConfig processingStep = PipelineStepConfig.builder()
            .stepName(PROCESSING_STEP_NAME)
            .stepType(StepType.SINK)
            .description("Processing step that receives from Kafka")
            .processorInfo(processorInfo)
            .build();
        
        return buildClusterConfig(PIPELINE_NAME, Map.of(
            KAFKA_STEP_NAME, kafkaStep,
            PROCESSING_STEP_NAME, processingStep
        ));
    }
    
    private PipelineClusterConfig createMultiStepPipelineConfig() {
        // Multiple Kafka inputs/outputs for complex testing
        KafkaInputDefinition input1 = KafkaInputDefinition.builder()
            .listenTopics(List.of("input-topic-1"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-multi-group-1")
            .build();
            
        KafkaInputDefinition input2 = KafkaInputDefinition.builder()
            .listenTopics(List.of("input-topic-2"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-multi-group-2")
            .build();
            
        PipelineStepConfig.ProcessorInfo processorInfo = PipelineStepConfig.ProcessorInfo.builder()
            .grpcServiceName("yappy-echo")
            .build();

        PipelineStepConfig step1 = PipelineStepConfig.builder()
            .stepName("multi-step-1")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(input1))
            .processorInfo(processorInfo)
            .build();
            
        PipelineStepConfig step2 = PipelineStepConfig.builder()
            .stepName("multi-step-2")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(input2))
            .processorInfo(processorInfo)
            .build();
        
        return buildClusterConfig("multi-step-pipeline", Map.of(
            "multi-step-1", step1,
            "multi-step-2", step2
        ));
    }
    
    private PipelineClusterConfig createUpdatedPipelineConfig(PipelineClusterConfig original) {
        // Add one more step to the configuration
        KafkaInputDefinition newInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("new-input-topic"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-new-group")
            .build();
            
        PipelineStepConfig newStep = PipelineStepConfig.builder()
            .stepName("new-step")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(newInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
            
        // Get existing steps and add new one
        Map<String, PipelineStepConfig> existingSteps = new HashMap<>(
            original.pipelineGraphConfig().pipelines().values().iterator().next().pipelineSteps());
        existingSteps.put("new-step", newStep);
        
        return buildClusterConfig("updated-pipeline", existingSteps);
    }
    
    private PipelineClusterConfig createMixedTransportPipelineConfig() {
        // Step with Kafka input and gRPC output
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
            .listenTopics(List.of(INPUT_TOPIC))
            .consumerGroupId(TEST_CLUSTER_NAME + "-mixed-group")
            .build();
            
        PipelineStepConfig mixedStep = PipelineStepConfig.builder()
            .stepName("mixed-transport-step")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(kafkaInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .outputs(Map.of(
                "grpc", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("grpc-step")
                    .transportType(TransportType.GRPC)
                    .grpcTransport(GrpcTransportConfig.builder()
                        .serviceName("test-grpc-service")
                        .build())
                    .build()
            ))
            .build();
            
        PipelineStepConfig grpcStep = PipelineStepConfig.builder()
            .stepName("grpc-step")
            .stepType(StepType.SINK)
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("test-grpc-service")
                .build())
            .build();
        
        return buildClusterConfig("mixed-transport-pipeline", Map.of(
            "mixed-transport-step", mixedStep,
            "grpc-step", grpcStep
        ));
    }
    
    private PipelineClusterConfig createErrorHandlingPipelineConfig() {
        KafkaInputDefinition input = KafkaInputDefinition.builder()
            .listenTopics(List.of(INPUT_TOPIC))
            .consumerGroupId(TEST_CLUSTER_NAME + "-error-group")
            .build();
            
        PipelineStepConfig errorStep = PipelineStepConfig.builder()
            .stepName("error-handling-step")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(input))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .outputs(Map.of(
                "error", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("error-handler")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("error-topic")
                        .build())
                    .build()
            ))
            .build();
        
        return buildClusterConfig("error-handling-pipeline", Map.of(
            "error-handling-step", errorStep
        ));
    }
    
    private PipelineClusterConfig buildClusterConfig(String pipelineName, Map<String, PipelineStepConfig> steps) {
        PipelineConfig pipeline = PipelineConfig.builder()
            .name(pipelineName)
            .pipelineSteps(steps)
            .build();
            
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
            .pipelines(Map.of(pipelineName, pipeline))
            .build();
            
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
            .availableModules(Map.of())
            .build();
        
        return PipelineClusterConfig.builder()
            .clusterName(TEST_CLUSTER_NAME)
            .pipelineGraphConfig(graphConfig)
            .pipelineModuleMap(moduleMap)
            .defaultPipelineName(pipelineName)
            .allowedKafkaTopics(Set.of(INPUT_TOPIC, OUTPUT_TOPIC, "chunked-documents", "error-topic"))
            .allowedGrpcServices(Set.of("test-grpc-service"))
            .build();
    }
    
    private void verifyPipelineEngineIntegration(PipeStream testMessage) {
        // Verify that the pipeline engine is properly integrated
        assertTrue(pipelineEngine.isRunning(), "Pipeline engine should be running");
        
        // Test direct pipeline processing
        StepVerifier.create(pipelineEngine.processMessage(testMessage))
            .verifyComplete();
            
        LOG.info("✅ Pipeline engine integration verified");
    }
    
    private void verifyEventDrivenArchitecture() {
        // The fact that our Kafka listeners were created automatically
        // and messages flowed through the system demonstrates the
        // event-driven architecture is working
        assertTrue(listenerPool.getListenerCount() > 0, 
            "Event-driven listener creation should be working");
            
        LOG.info("✅ Event-driven architecture verified");
    }
}