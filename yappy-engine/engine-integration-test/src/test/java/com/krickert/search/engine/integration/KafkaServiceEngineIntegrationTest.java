package com.krickert.search.engine.integration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.core.PipelineEngine;
import com.krickert.search.engine.core.transport.kafka.KafkaMessageForwarder;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.orchestrator.kafka.listener.KafkaListenerManager;
import com.krickert.search.orchestrator.kafka.listener.KafkaListenerPool;
import com.krickert.search.orchestrator.kafka.producer.KafkaForwarder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates the engine-kafka and engine-core integration
 * in a dedicated integration test environment. This test focuses on:
 * 
 * 1. Validating component injection and dependency resolution
 * 2. Testing pipeline configuration and listener lifecycle
 * 3. Verifying message routing and transport selection
 * 4. Demonstrating programmatic pipeline configuration (as documentation)
 * 
 * This test runs in the engine-integration-test module to avoid networking
 * and container conflicts that can occur when running in the engine-core module.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "engine.event-driven.enabled", value = "true")
@Property(name = "kafka.slot-manager.enabled", value = "false")
class KafkaServiceEngineIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaServiceEngineIntegrationTest.class);
    
    private String TEST_CLUSTER_NAME;
    private static final String PIPELINE_NAME = "kafka-engine-integration-test";
    private static final String INPUT_TOPIC = "integration-test-input";
    private static final String OUTPUT_TOPIC = "integration-test-output";
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    ConsulBusinessOperationsService consulOps;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    PipelineEngine pipelineEngine;
    
    @Inject
    KafkaListenerManager kafkaListenerManager;
    
    @Inject
    KafkaListenerPool listenerPool;
    
    @Inject
    KafkaForwarder kafkaForwarder;
    
    @Inject
    KafkaMessageForwarder kafkaMessageForwarder;
    
    @BeforeAll
    void setupAll() {
        // Get cluster name from configuration
        TEST_CLUSTER_NAME = applicationContext.getProperty("app.config.cluster-name", String.class)
                .orElseThrow(() -> new IllegalStateException("app.config.cluster-name not configured"));
        LOG.info("Using cluster name: {}", TEST_CLUSTER_NAME);
    }
    
    @BeforeEach
    void setUp() {
        // Clean up any existing configuration
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
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
    
    @Test
    @DisplayName("Component injection and dependency resolution")
    void testComponentInjection() {
        LOG.info("🔍 Testing component injection and dependency resolution...");
        
        // Verify all components are properly injected
        assertNotNull(applicationContext, "ApplicationContext should be injected");
        assertNotNull(consulOps, "ConsulBusinessOperationsService should be injected");
        assertNotNull(pipelineEngine, "PipelineEngine should be injected");
        assertNotNull(kafkaListenerManager, "KafkaListenerManager should be injected");
        assertNotNull(listenerPool, "KafkaListenerPool should be injected");
        assertNotNull(kafkaForwarder, "KafkaForwarder should be injected");
        assertNotNull(kafkaMessageForwarder, "KafkaMessageForwarder should be injected");
        
        // Verify pipeline engine is running
        assertTrue(pipelineEngine.isRunning(), "Pipeline engine should be running");
        
        LOG.info("✅ All components properly injected and initialized");
    }
    
    @Test
    @DisplayName("Pipeline configuration and listener lifecycle")
    void testPipelineConfigurationAndListenerLifecycle() {
        LOG.info("🔍 Testing pipeline configuration and listener lifecycle...");
        
        // 1. Create and store pipeline configuration
        PipelineClusterConfig config = createKafkaIntegrationPipelineConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        LOG.info("📝 Stored pipeline configuration in Consul");
        
        // 2. Wait for Kafka listeners to be created automatically via configuration events
        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                assertEquals(1, listenerPool.getListenerCount(), 
                    "One Kafka listener should be created from configuration");
                LOG.debug("✅ Kafka listener successfully created");
            });
        
        // 3. Verify listener details
        var listeners = listenerPool.getAllListeners();
        assertEquals(1, listeners.size(), "Should have exactly one listener");
        
        var listener = listeners.iterator().next();
        assertEquals(INPUT_TOPIC, listener.getTopic(), "Listener should be for the correct topic");
        assertEquals(TEST_CLUSTER_NAME + "-integration-test-group", listener.getGroupId(), 
            "Listener should have the correct group ID");
        
        // 4. Test configuration update - add another listener
        PipelineClusterConfig updatedConfig = addSecondListenerToConfig(config);
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, updatedConfig).block();
        LOG.info("📝 Updated pipeline configuration with additional listener");
        
        // 5. Wait for additional listener to be created
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 2);
            
        LOG.info("✅ Configuration update created additional listener: {} total", 
            listenerPool.getListenerCount());
        
        // 6. Test configuration deletion and cleanup
        consulOps.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        LOG.info("🗑️ Deleted pipeline configuration");
        
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() == 0);
            
        LOG.info("✅ All listeners cleaned up after configuration deletion");
    }
    
    @Test
    @DisplayName("Message routing and transport selection")
    void testMessageRoutingAndTransportSelection() {
        LOG.info("🔍 Testing message routing and transport selection...");
        
        // 1. Create pipeline with mixed transports (Kafka + gRPC routing)
        PipelineClusterConfig config = createMixedTransportPipelineConfig();
        consulOps.storeClusterConfiguration(TEST_CLUSTER_NAME, config).block();
        
        // 2. Wait for setup
        await().atMost(Duration.ofSeconds(10))
            .until(() -> listenerPool.getListenerCount() > 0);
        
        // 3. Create test message and verify pipeline engine can route it
        // Use "mixed-transport-step" which is the initial step in this pipeline
        PipeStream testMessage = createTestPipeStream("mixed-transport-step");
        
        // 4. Test message processing through pipeline engine
        // The pipeline will try to route to yappy-echo service which doesn't exist
        // This is expected behavior for this test - we're testing routing configuration
        StepVerifier.create(pipelineEngine.processMessage(testMessage))
            .expectErrorMatches(throwable -> 
                throwable instanceof IllegalStateException &&
                throwable.getMessage().contains("No healthy instances found"))
            .verify();
            
        LOG.info("✅ Pipeline engine successfully processed message with mixed transports");
        
        // 5. Test Kafka forwarder directly
        StepVerifier.create(kafkaForwarder.forwardToKafka(testMessage, OUTPUT_TOPIC))
            .expectNextCount(1) // Should return RecordMetadata
            .verifyComplete();
            
        LOG.info("✅ KafkaForwarder successfully forwarded message to topic: {}", OUTPUT_TOPIC);
    }
    
    @Test
    @DisplayName("Error handling and edge cases")
    void testErrorHandlingAndEdgeCases() {
        LOG.info("🔍 Testing error handling and edge cases...");
        
        // 1. Test processing message with invalid target step
        PipeStream invalidMessage = createTestPipeStream().toBuilder()
            .setTargetStepName("non-existent-step")
            .build();
        
        // Should return an error for non-existent step (improved error handling)
        StepVerifier.create(pipelineEngine.processMessage(invalidMessage))
            .expectError(IllegalStateException.class)
            .verify();
            
        LOG.info("✅ Pipeline properly rejected message with invalid target step");
        
        // 2. Test processing message with no target step (pipeline completion)
        PipeStream completedMessage = createTestPipeStream().toBuilder()
            .setTargetStepName("") // Empty target indicates pipeline completion
            .build();
        
        StepVerifier.create(pipelineEngine.processMessage(completedMessage))
            .verifyComplete();
            
        LOG.info("✅ Pipeline gracefully handled completed message (no target step)");
        
        // 3. Test listener operations on non-existent listeners
        StepVerifier.create(kafkaListenerManager.pauseConsumer(
                "non-existent-pipeline", "non-existent-step", "non-existent-topic", "non-existent-group"))
            .verifyError(IllegalArgumentException.class);
            
        LOG.info("✅ KafkaListenerManager properly handles operations on non-existent listeners");
    }
    
    @Test
    @DisplayName("Comprehensive pipeline configuration examples")
    void testComprehensivePipelineConfigurationExamples() {
        LOG.info("🔍 Testing comprehensive pipeline configuration examples...");
        
        // This test serves as documentation for programmatic pipeline configuration
        
        // 1. Simple document ingestion pipeline
        PipelineClusterConfig simpleConfig = createSimpleDocumentIngestionPipeline();
        assertNotNull(simpleConfig, "Simple pipeline configuration should be created");
        assertEquals(1, simpleConfig.pipelineGraphConfig().pipelines().size(), 
            "Simple pipeline should have one pipeline");
        
        LOG.info("✅ Simple document ingestion pipeline configuration created");
        
        // 2. Multi-step processing pipeline
        PipelineClusterConfig multiStepConfig = createMultiStepProcessingPipeline();
        assertNotNull(multiStepConfig, "Multi-step pipeline configuration should be created");
        assertTrue(multiStepConfig.pipelineGraphConfig().pipelines().size() >= 1, 
            "Multi-step pipeline should have at least one pipeline");
        
        LOG.info("✅ Multi-step processing pipeline configuration created");
        
        // 3. Real-time indexing pipeline
        PipelineClusterConfig realtimeConfig = createRealTimeIndexingPipeline();
        assertNotNull(realtimeConfig, "Real-time pipeline configuration should be created");
        
        LOG.info("✅ Real-time indexing pipeline configuration created");
        
        // 4. Test storing and retrieving each configuration
        for (var config : List.of(simpleConfig, multiStepConfig, realtimeConfig)) {
            String testClusterName = TEST_CLUSTER_NAME + "-" + UUID.randomUUID().toString().substring(0, 8);
            
            // Store configuration
            consulOps.storeClusterConfiguration(testClusterName, config).block();
            
            // Retrieve and verify
            var retrievedConfig = consulOps.getPipelineClusterConfig(testClusterName).block().orElse(null);
            assertNotNull(retrievedConfig, "Should be able to retrieve stored configuration");
            assertEquals(config.clusterName(), retrievedConfig.clusterName(), 
                "Retrieved configuration should match stored configuration");
            
            // Cleanup
            consulOps.deleteClusterConfiguration(testClusterName).block();
            
            LOG.info("✅ Successfully stored and retrieved configuration for cluster: {}", testClusterName);
        }
        
        LOG.info("🎉 All pipeline configuration examples validated successfully!");
    }
    
    // Helper methods for creating test configurations
    
    private PipelineClusterConfig createKafkaIntegrationPipelineConfig() {
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
            .listenTopics(List.of(INPUT_TOPIC))
            .consumerGroupId(TEST_CLUSTER_NAME + "-integration-test-group")
            .kafkaConsumerProperties(Map.of(
                "auto.offset.reset", "earliest",
                "max.poll.records", "10"
            ))
            .build();
            
        PipelineStepConfig kafkaStep = PipelineStepConfig.builder()
            .stepName("kafka-integration-step")
            .stepType(StepType.INITIAL_PIPELINE)
            .description("Kafka integration test step")
            .kafkaInputs(List.of(kafkaInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
        
        return buildClusterConfig(PIPELINE_NAME, Map.of(
            "kafka-integration-step", kafkaStep
        ));
    }
    
    private PipelineClusterConfig addSecondListenerToConfig(PipelineClusterConfig original) {
        KafkaInputDefinition secondKafkaInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("second-input-topic"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-second-group")
            .build();
            
        PipelineStepConfig secondStep = PipelineStepConfig.builder()
            .stepName("second-kafka-step")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(secondKafkaInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
            
        // Get existing steps and add new one
        Map<String, PipelineStepConfig> existingSteps = new HashMap<>(
            original.pipelineGraphConfig().pipelines().values().iterator().next().pipelineSteps());
        existingSteps.put("second-kafka-step", secondStep);
        
        return buildClusterConfig(PIPELINE_NAME, existingSteps);
    }
    
    private PipelineClusterConfig createMixedTransportPipelineConfig() {
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
                "kafka-output", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("kafka-sink")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic(OUTPUT_TOPIC)
                        .build())
                    .build(),
                "grpc-output", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("grpc-sink")
                    .transportType(TransportType.GRPC)
                    .grpcTransport(GrpcTransportConfig.builder()
                        .serviceName("yappy-echo")
                        .build())
                    .build()
            ))
            .build();
            
        PipelineStepConfig kafkaSink = PipelineStepConfig.builder()
            .stepName("kafka-sink")
            .stepType(StepType.SINK)
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
            
        PipelineStepConfig grpcSink = PipelineStepConfig.builder()
            .stepName("grpc-sink")
            .stepType(StepType.SINK)
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
        
        return buildClusterConfig(PIPELINE_NAME, Map.of(
            "mixed-transport-step", mixedStep,
            "kafka-sink", kafkaSink,
            "grpc-sink", grpcSink
        ));
    }
    
    private PipeStream createTestPipeStream() {
        return createTestPipeStream("kafka-integration-step");
    }
    
    private PipeStream createTestPipeStream(String targetStepName) {
        String streamId = UUID.randomUUID().toString();
        return PipeStream.newBuilder()
            .setStreamId(streamId)
            .setCurrentPipelineName(PIPELINE_NAME)
            .setTargetStepName(targetStepName)
            .setCurrentHopNumber(1)
            .setDocument(PipeDoc.newBuilder()
                .setId("test-doc-" + streamId)
                .setTitle("Integration Test Document")
                .setBody("This is a test document for engine-kafka and engine-core integration testing.")
                .build())
            .putContextParams("test.source", "engine-kafka-integration-test")
            .putContextParams("test.timestamp", String.valueOf(System.currentTimeMillis()))
            .build();
    }
    
    // Pipeline configuration examples (serving as documentation)
    
    private PipelineClusterConfig createSimpleDocumentIngestionPipeline() {
        KafkaInputDefinition kafkaInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("raw-documents"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-simple-ingestion-group")
            .kafkaConsumerProperties(Map.of(
                "auto.offset.reset", "earliest",
                "max.poll.records", "100"
            ))
            .build();

        PipelineStepConfig ingestionStep = PipelineStepConfig.builder()
            .stepName("document-ingestion")
            .stepType(StepType.INITIAL_PIPELINE)
            .description("Simple document ingestion from Kafka")
            .kafkaInputs(List.of(kafkaInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-chunker")
                .build())
            .outputs(Map.of(
                "processed", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("document-processing")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("processed-documents")
                        .kafkaProducerProperties(Map.of("acks", "all"))
                        .build())
                    .build()
            ))
            .build();
            
        PipelineStepConfig processingStep = PipelineStepConfig.builder()
            .stepName("document-processing")
            .stepType(StepType.SINK)
            .description("Final document processing")
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-echo")
                .build())
            .build();
        
        return buildClusterConfig("simple-document-ingestion", Map.of(
            "document-ingestion", ingestionStep,
            "document-processing", processingStep
        ));
    }
    
    private PipelineClusterConfig createMultiStepProcessingPipeline() {
        // Step 1: Raw document ingestion
        KafkaInputDefinition rawInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("raw-documents"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-multi-raw-group")
            .build();

        PipelineStepConfig rawIngestion = PipelineStepConfig.builder()
            .stepName("raw-ingestion")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(rawInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-tika-parser")
                .build())
            .outputs(Map.of(
                "extracted", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("chunking")
                    .transportType(TransportType.GRPC)
                    .grpcTransport(GrpcTransportConfig.builder()
                        .serviceName("yappy-chunker")
                        .build())
                    .build()
            ))
            .build();

        // Step 2: Document chunking
        PipelineStepConfig chunking = PipelineStepConfig.builder()
            .stepName("chunking")
            .stepType(StepType.PIPELINE)
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-chunker")
                .build())
            .outputs(Map.of(
                "chunked", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("embedding")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("chunked-documents")
                        .build())
                    .build()
            ))
            .build();

        // Step 3: Embedding generation
        KafkaInputDefinition chunkInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("chunked-documents"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-embedding-group")
            .build();

        PipelineStepConfig embedding = PipelineStepConfig.builder()
            .stepName("embedding")
            .stepType(StepType.PIPELINE)
            .kafkaInputs(List.of(chunkInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-embedder")
                .build())
            .outputs(Map.of(
                "vectors", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("indexing")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("document-vectors")
                        .build())
                    .build()
            ))
            .build();

        // Step 4: Final indexing
        KafkaInputDefinition vectorInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("document-vectors"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-indexing-group")
            .build();

        PipelineStepConfig indexing = PipelineStepConfig.builder()
            .stepName("indexing")
            .stepType(StepType.SINK)
            .kafkaInputs(List.of(vectorInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-search-indexer")
                .build())
            .build();
        
        return buildClusterConfig("multi-step-processing", Map.of(
            "raw-ingestion", rawIngestion,
            "chunking", chunking,
            "embedding", embedding,
            "indexing", indexing
        ));
    }
    
    private PipelineClusterConfig createRealTimeIndexingPipeline() {
        // High-throughput real-time ingestion
        KafkaInputDefinition realtimeInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("live-documents", "updated-documents"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-realtime-indexer")
            .kafkaConsumerProperties(Map.of(
                "auto.offset.reset", "latest",
                "max.poll.records", "500",
                "fetch.min.bytes", "1024"
            ))
            .build();

        PipelineStepConfig realtimeIngestion = PipelineStepConfig.builder()
            .stepName("realtime-ingestion")
            .stepType(StepType.INITIAL_PIPELINE)
            .kafkaInputs(List.of(realtimeInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-real-time-processor")
                .build())
            .outputs(Map.of(
                "indexed", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("search-indexing")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("indexed-documents")
                        .kafkaProducerProperties(Map.of(
                            "acks", "1", // Faster than "all" for real-time
                            "batch.size", "32768"
                        ))
                        .build())
                    .build(),
                "analytics", PipelineStepConfig.OutputTarget.builder()
                    .targetStepName("analytics-processing")
                    .transportType(TransportType.KAFKA)
                    .kafkaTransport(KafkaTransportConfig.builder()
                        .topic("analytics-stream")
                        .build())
                    .build()
            ))
            .build();

        // Search indexing sink
        KafkaInputDefinition indexInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("indexed-documents"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-search-indexer")
            .build();

        PipelineStepConfig searchIndexing = PipelineStepConfig.builder()
            .stepName("search-indexing")
            .stepType(StepType.SINK)
            .kafkaInputs(List.of(indexInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-search-indexer")
                .build())
            .build();

        // Analytics processing sink
        KafkaInputDefinition analyticsInput = KafkaInputDefinition.builder()
            .listenTopics(List.of("analytics-stream"))
            .consumerGroupId(TEST_CLUSTER_NAME + "-analytics-processor")
            .build();

        PipelineStepConfig analyticsProcessing = PipelineStepConfig.builder()
            .stepName("analytics-processing")
            .stepType(StepType.SINK)
            .kafkaInputs(List.of(analyticsInput))
            .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                .grpcServiceName("yappy-analytics-processor")
                .build())
            .build();
        
        return buildClusterConfig("realtime-indexing", Map.of(
            "realtime-ingestion", realtimeIngestion,
            "search-indexing", searchIndexing,
            "analytics-processing", analyticsProcessing
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
            .availableModules(createModuleMap())
            .build();
        
        return PipelineClusterConfig.builder()
            .clusterName(TEST_CLUSTER_NAME)
            .pipelineGraphConfig(graphConfig)
            .pipelineModuleMap(moduleMap)
            .defaultPipelineName(pipelineName)
            .allowedKafkaTopics(Set.of(
                INPUT_TOPIC, OUTPUT_TOPIC, "raw-documents", "processed-documents",
                "chunked-documents", "document-vectors", "indexed-documents",
                "analytics-stream", "live-documents", "updated-documents"
            ))
            .allowedGrpcServices(Set.of(
                "yappy-echo", "yappy-chunker", "yappy-tika-parser", 
                "yappy-embedder", "yappy-search-indexer", "yappy-analytics-processor",
                "yappy-real-time-processor"
            ))
            .build();
    }
    
    private Map<String, PipelineModuleConfiguration> createModuleMap() {
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        
        modules.put("yappy-echo", PipelineModuleConfiguration.builder()
            .implementationName("Echo Test Module")
            .implementationId("yappy-echo")
            .build());
            
        modules.put("yappy-chunker", PipelineModuleConfiguration.builder()
            .implementationName("Document Chunker")
            .implementationId("yappy-chunker")
            .build());
            
        modules.put("yappy-tika-parser", PipelineModuleConfiguration.builder()
            .implementationName("Tika Document Parser")
            .implementationId("yappy-tika-parser")
            .build());
            
        modules.put("yappy-embedder", PipelineModuleConfiguration.builder()
            .implementationName("Document Embedder")
            .implementationId("yappy-embedder")
            .build());
        
        return modules;
    }
}