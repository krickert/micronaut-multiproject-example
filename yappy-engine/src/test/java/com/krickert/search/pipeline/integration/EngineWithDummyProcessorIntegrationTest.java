package com.krickert.search.pipeline.integration;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.admin.PipelineKafkaTopicService;
import com.krickert.search.pipeline.test.dummy.DummyPipeStepProcessor;
import com.krickert.search.pipeline.test.dummy.StandaloneDummyGrpcServer;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.UUIDDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that demonstrates engine processing with the DummyPipeStepProcessor.
 * This test:
 * 1. Runs the DummyPipeStepProcessor as a gRPC service (via Micronaut)
 * 2. Configures a pipeline to use the dummy processor
 * 3. Sends documents through the engine
 * 4. Verifies the processed output in Kafka
 */
@MicronautTest(environments = {"test", "engine-dummy-test"})
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = EngineWithDummyProcessorIntegrationTest.TEST_CLUSTER_NAME)
@Property(name = "app.engine.bootstrapper.enabled", value = "false")

// Disable Micronaut's gRPC server management
@Property(name = "grpc.server.enabled", value = "false")

// Producer configuration
@Property(name = "kafka.producers.pipestream-forwarder.key.serializer", value = "org.apache.kafka.common.serialization.UUIDSerializer")
@Property(name = "kafka.producers.pipestream-forwarder.value.serializer", value = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.AUTO_REGISTER_ARTIFACT, value = "true")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
class EngineWithDummyProcessorIntegrationTest {

    static final String TEST_CLUSTER_NAME = "engineDummyProcessorTestCluster";
    private static final Logger LOG = LoggerFactory.getLogger(EngineWithDummyProcessorIntegrationTest.class);
    
    private final String testRunId = UUID.randomUUID().toString().substring(0, 8);
    private final String TEST_PIPELINE_NAME = "dummy-processor-pipeline-" + testRunId;
    private final String TEST_STEP_NAME = "dummy-step-" + testRunId;
    
    @Inject
    PipeStreamEngine pipeStreamEngine;
    
    @Inject
    PipelineKafkaTopicService pipelineKafkaTopicService;
    
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;
    
    @Inject
    DynamicConfigurationManager dynamicConfigurationManager;
    
    @Inject
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Inject
    @Property(name = "apicurio.registry.url")
    String apicurioRegistryUrl;
    
    private StandaloneDummyGrpcServer dummyGrpcServer;
    private static final int GRPC_SERVER_PORT = 50061; // Fixed port for test
    private KafkaConsumer<UUID, PipeStream> testConsumer;
    private String outputTopic;

    @BeforeEach
    void setUp() throws Exception {
        LOG.info("Setting up test with standalone gRPC server on port {}", GRPC_SERVER_PORT);
        
        // Create and start the standalone gRPC server on fixed port
        DummyPipeStepProcessor processor = new DummyPipeStepProcessor("append", " [PROCESSED]");
        dummyGrpcServer = new StandaloneDummyGrpcServer(GRPC_SERVER_PORT, processor);
        dummyGrpcServer.start();
        
        // Wait for server to start
        await().atMost(5, TimeUnit.SECONDS)
            .until(() -> dummyGrpcServer.isRunning());
        
        // Clean up any existing configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Create topics for the pipeline step
        pipelineKafkaTopicService.createAllTopics(TEST_PIPELINE_NAME, TEST_STEP_NAME);
        outputTopic = pipelineKafkaTopicService.generateTopicName(TEST_PIPELINE_NAME, TEST_STEP_NAME, PipelineKafkaTopicService.TopicType.OUTPUT);
        
        // Wait for topics to be created
        await().atMost(10, TimeUnit.SECONDS)
            .until(() -> pipelineKafkaTopicService.listTopicsForStep(TEST_PIPELINE_NAME, TEST_STEP_NAME).size() == 4);
        
        // Create pipeline configuration that uses the dummy processor
        // Configure it to connect to our local gRPC server on the random port
        PipelineStepConfig step = PipelineStepConfig.builder()
                .stepName(TEST_STEP_NAME)
                .stepType(StepType.PIPELINE)
                .processorInfo(new PipelineStepConfig.ProcessorInfo("dummy-service", null))
                .outputs(Map.of("kafka", new PipelineStepConfig.OutputTarget(
                    outputTopic, 
                    TransportType.KAFKA,
                    null,
                    new KafkaTransportConfig(outputTopic, null)
                )))
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE_NAME)
                .pipelineSteps(Map.of(TEST_STEP_NAME, step))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE_NAME, testPipeline))
                .build();
        
        PipelineModuleConfiguration dummyModuleConfig = PipelineModuleConfiguration.builder()
                .implementationId("dummy-service")
                .implementationName("Dummy Test Service")
                .build();
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(Map.of("dummy-service", dummyModuleConfig))
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(outputTopic))
                .allowedGrpcServices(Set.of("dummy-service"))
                .build();
        
        // Store configuration in Consul
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        
        // Wait for configuration to be loaded
        await().atMost(10, TimeUnit.SECONDS)
                .until(() -> dynamicConfigurationManager.getCurrentPipelineClusterConfig().isPresent());
        
        // Create a test consumer
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUIDDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        consumerProps.put(SerdeConfig.REGISTRY_URL, apicurioRegistryUrl);
        consumerProps.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        consumerProps.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, "io.apicurio.registry.serde.strategy.TopicIdStrategy");
        
        testConsumer = new KafkaConsumer<>(consumerProps);
        testConsumer.subscribe(Collections.singletonList(outputTopic));
        
        LOG.info("Test setup complete. Output topic: {}", outputTopic);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (testConsumer != null) {
            testConsumer.close();
        }
        
        // Stop the gRPC server
        if (dummyGrpcServer != null) {
            dummyGrpcServer.stop();
        }
        
        // Clean up configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        LOG.info("Test teardown complete");
    }
    
    @Test
    @DisplayName("Should process document through dummy processor and output to Kafka")
    void testProcessWithDummyProcessor() {
        // Create test document
        String streamId = "test-stream-" + UUID.randomUUID();
        String originalTitle = "Test Document";
        
        PipeStream input = PipeStream.newBuilder()
                .setStreamId(streamId)
                .setDocument(PipeDoc.newBuilder()
                    .setId("doc-1")
                    .setTitle(originalTitle)
                    .build())
                .setCurrentPipelineName(TEST_PIPELINE_NAME)
                .setTargetStepName(TEST_STEP_NAME)
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Sending PipeStream {} to engine for processing", streamId);
        
        // Process through engine
        pipeStreamEngine.processStream(input);
        
        // Wait for processed document in Kafka
        final PipeStream[] processedHolder = new PipeStream[1];
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        if (record.value().getStreamId().equals(streamId)) {
                            LOG.info("Found processed document in Kafka: {}", record.value().getStreamId());
                            processedHolder[0] = record.value(); // Store the processed message
                            return true;
                        }
                    }
                    return false;
                });
        
        PipeStream processed = processedHolder[0];
        
        // Verify the document was processed
        assertNotNull(processed, "Should have received processed document from Kafka");
        assertEquals(streamId, processed.getStreamId());
        assertEquals("doc-1", processed.getDocument().getId());
        
        // With append behavior, the title should have the suffix added
        String expectedTitle = originalTitle + " [PROCESSED]";
        assertEquals(expectedTitle, processed.getDocument().getTitle(), 
            "Title should be modified by dummy processor");
        
        // The dummy processor adds metadata to the document's custom_data, not the stream
        // We would need to check processed.getDocument().getCustomData() if we want to verify this
        
        // Verify hop number incremented
        assertTrue(processed.getCurrentHopNumber() > 0, 
            "Hop number should have been incremented");
        
        LOG.info("Successfully processed document through dummy processor");
    }
    
    @Test
    @DisplayName("Should process multiple documents in sequence")
    void testProcessMultipleDocuments() {
        int numDocuments = 5;
        List<String> streamIds = new ArrayList<>();
        
        // Send multiple documents
        for (int i = 0; i < numDocuments; i++) {
            String streamId = "multi-stream-" + i;
            streamIds.add(streamId);
            
            PipeStream input = PipeStream.newBuilder()
                    .setStreamId(streamId)
                    .setDocument(PipeDoc.newBuilder()
                        .setId("doc-" + i)
                        .setTitle("Document " + i)
                        .build())
                    .setCurrentPipelineName(TEST_PIPELINE_NAME)
                    .setTargetStepName(TEST_STEP_NAME)
                    .setCurrentHopNumber(0)
                    .build();
            
            pipeStreamEngine.processStream(input);
        }
        
        LOG.info("Sent {} documents for processing", numDocuments);
        
        // Collect processed documents
        Map<String, PipeStream> processedDocs = new HashMap<>();
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    ConsumerRecords<UUID, PipeStream> records = testConsumer.poll(Duration.ofMillis(1000));
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        String streamId = record.value().getStreamId();
                        if (streamIds.contains(streamId)) {
                            processedDocs.put(streamId, record.value());
                        }
                    }
                    return processedDocs.size() == numDocuments;
                });
        
        // Verify all documents were processed
        assertEquals(numDocuments, processedDocs.size());
        
        for (int i = 0; i < numDocuments; i++) {
            String streamId = "multi-stream-" + i;
            PipeStream processed = processedDocs.get(streamId);
            assertNotNull(processed, "Should have processed document " + i);
            assertEquals("Document " + i + " [PROCESSED]", processed.getDocument().getTitle());
        }
        
        LOG.info("Successfully processed {} documents", numDocuments);
    }
}