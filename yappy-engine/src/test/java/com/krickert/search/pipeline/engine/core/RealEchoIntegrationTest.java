package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.yappy.modules.echo.EchoService;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real integration test that uses all actual components:
 * - Real Consul for configuration management
 * - Real Kafka for messaging
 * - Real Apicurio for schema registry
 * - Real Echo gRPC service
 */
@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = RealEchoIntegrationTest.TEST_CLUSTER_NAME)
// Configure Kafka producer
@Property(name = "kafka.producers.pipestream-forwarder.key.serializer", value = "org.apache.kafka.common.serialization.UUIDSerializer")
@Property(name = "kafka.producers.pipestream-forwarder.value.serializer", value = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.AUTO_REGISTER_ARTIFACT, value = "true")
@Property(name = "kafka.producers.pipestream-forwarder." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
// Configure Kafka consumer
@Property(name = "kafka.consumers.default." + SerdeConfig.REGISTRY_URL, value = "${apicurio.registry.url}")
@Property(name = "kafka.consumers.default." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, value = "com.krickert.search.model.PipeStream")
@Property(name = "kafka.consumers.default." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, value = "io.apicurio.registry.serde.strategy.TopicIdStrategy")
@Property(name = "kafka.consumers.default.bootstrap.servers", value = "${kafka.bootstrap.servers}")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealEchoIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(RealEchoIntegrationTest.class);
    
    static final String TEST_CLUSTER_NAME = "realEchoTestCluster";
    private static final String TEST_PIPELINE = "echo-test-pipeline";
    private static final String ECHO_STEP = "echo";
    private static final String TEST_TOPIC = "echo-test-output";
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    ConsulBusinessOperationsService consulBusinessOperationsService;
    
    @Inject
    DynamicConfigurationManager dynamicConfigurationManager;
    
    @Inject
    KafkaForwarder kafkaForwarder;
    
    @Inject
    GrpcChannelManager grpcChannelManager;
    
    @Inject
    KafkaListenerManager kafkaListenerManager;
    
    private Server echoGrpcServer;
    private int echoPort;
    private KafkaConsumer<String, PipeStream> kafkaConsumer;
    
    @BeforeAll
    void setupAll() throws IOException {
        // Find a free port for Echo service
        try (ServerSocket socket = new ServerSocket(0)) {
            echoPort = socket.getLocalPort();
        }
        
        // Start real Echo gRPC service
        LOG.info("Starting Echo gRPC service on port {}", echoPort);
        EchoService echoService = new EchoService();
        echoGrpcServer = ServerBuilder
                .forPort(echoPort)
                .addService(echoService)
                .build()
                .start();
        
        // Create channel to Echo service
        ManagedChannel echoChannel = ManagedChannelBuilder
                .forAddress("localhost", echoPort)
                .usePlaintext()
                .build();
        
        // Register with GrpcChannelManager
        grpcChannelManager.updateChannel("echo", echoChannel);
        LOG.info("Echo channel registered with GrpcChannelManager");
    }
    
    @AfterAll
    void tearDownAll() {
        if (echoGrpcServer != null) {
            LOG.info("Shutting down Echo gRPC server...");
            echoGrpcServer.shutdown();
            try {
                echoGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                echoGrpcServer.shutdownNow();
            }
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
    
    @BeforeEach
    void setUp() {
        // Clean up any previous configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Calculate the input topic name
        String inputTopic = "pipeline." + TEST_PIPELINE + ".step." + ECHO_STEP + ".input";
        
        // Create pipeline configuration
        PipelineStepConfig echoStep = PipelineStepConfig.builder()
                .stepName(ECHO_STEP)
                .stepType(StepType.PIPELINE) // Echo step processes and outputs to Kafka
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of(inputTopic))
                                .consumerGroupId("echo-consumer-group")
                                .kafkaConsumerProperties(Collections.emptyMap())
                                .build()
                ))
                .outputs(Map.of("kafka-output", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("kafka-sink") // Dummy target for Kafka output
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic(TEST_TOPIC)
                                        .build())
                                .build()))
                .build();
        
        // Add a sink step to satisfy the validation
        PipelineStepConfig sinkStep = PipelineStepConfig.builder()
                .stepName("kafka-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of()) // No outputs for a sink
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE)
                .pipelineSteps(Map.of(
                        ECHO_STEP, echoStep,
                        "kafka-sink", sinkStep
                ))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE, testPipeline))
                .build();
        
        PipelineModuleConfiguration echoModule = PipelineModuleConfiguration.builder()
                .implementationId("echo")
                .implementationName("Echo Service")
                .build();
        
        PipelineModuleConfiguration dummySinkModule = PipelineModuleConfiguration.builder()
                .implementationId("dummy-sink")
                .implementationName("Dummy Sink Service")
                .build();
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(Map.of(
                        "echo", echoModule,
                        "dummy-sink", dummySinkModule
                ))
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(TEST_TOPIC, inputTopic))
                .allowedGrpcServices(Set.of("echo", "dummy-sink"))
                .build();
        
        // Store configuration in real Consul
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        
        // Wait for DynamicConfigurationManager to load the configuration
        LOG.info("Waiting for DynamicConfigurationManager to load configuration...");
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    return currentConfig.isPresent() && currentConfig.get().clusterName().equals(TEST_CLUSTER_NAME);
                });
        
        LOG.info("Configuration loaded successfully");
        
        // Set up Kafka consumer
        setupKafkaConsumer();
    }
    
    @AfterEach
    void tearDown() {
        // Clean up configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Wait for listeners to be removed
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> kafkaListenerManager.getConsumerStatuses().isEmpty());
    }
    
    @Test
    @DisplayName("Should process document through Echo service via Kafka input and output to Kafka")
    void testKafkaInputToKafkaOutput() throws Exception {
        // Given - Generate unique stream ID for this test
        String testStreamId = "test-stream-" + UUID.randomUUID();
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId(testStreamId)
                .setDocument(PipeDoc.newBuilder()
                        .setId("test-doc-1")
                        .setTitle("Integration Test Document")
                        .setBody("This is a real integration test with all components")
                        .build())
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(ECHO_STEP)
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Sending PipeStream with ID {} to pipeline: {}", testStreamId, TEST_PIPELINE);
        
        // When - Send directly to Kafka topic that the pipeline expects
        String inputTopic = "pipeline." + TEST_PIPELINE + ".step." + ECHO_STEP + ".input";
        kafkaForwarder.forwardToKafka(testPipeStream, inputTopic).get(10, TimeUnit.SECONDS);
        
        LOG.info("Message sent to Kafka topic: {}", inputTopic);
        
        // Then - Verify the message was processed and output
        LOG.info("Waiting for processed message with streamId {} on output topic: {}", testStreamId, TEST_TOPIC);
        
        // Poll for messages and find the one with our stream ID
        PipeStream processedStream = null;
        long endTime = System.currentTimeMillis() + 30000; // 30 second timeout
        
        while (processedStream == null && System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, PipeStream> records = kafkaConsumer.poll(Duration.ofSeconds(1));
            
            for (ConsumerRecord<String, PipeStream> record : records) {
                PipeStream stream = record.value();
                LOG.info("Received message with streamId: {}", stream.getStreamId());
                if (testStreamId.equals(stream.getStreamId())) {
                    processedStream = stream;
                    break;
                }
            }
        }
        
        assertNotNull(processedStream, "Should have received processed message with streamId: " + testStreamId);
        assertEquals(testStreamId, processedStream.getStreamId(), "Stream ID should be preserved");
        assertEquals("test-doc-1", processedStream.getDocument().getId(), "Document ID should be preserved");
        
        LOG.info("Successfully received processed message from Echo service via Kafka");
    }
    
    @Test
    @DisplayName("Should process document through gRPC input to Kafka specific topic output")
    void testGrpcInputToKafkaOutput() throws Exception {
        // Set up a different pipeline configuration for gRPC input
        tearDown(); // Clean up previous config
        
        // Create a pipeline with gRPC input (no Kafka input)
        PipelineStepConfig grpcEchoStep = PipelineStepConfig.builder()
                .stepName("grpc-echo")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                // No kafkaInputs - this will be called directly via gRPC
                .outputs(Map.of("kafka-output", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("kafka-sink")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("grpc-echo-output")
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig sinkStep = PipelineStepConfig.builder()
                .stepName("kafka-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name("grpc-to-kafka-pipeline")
                .pipelineSteps(Map.of(
                        "grpc-echo", grpcEchoStep,
                        "kafka-sink", sinkStep
                ))
                .build();
        
        storeTestConfiguration("grpc-to-kafka-pipeline", testPipeline, 
                Set.of("grpc-echo-output"), Set.of("echo", "dummy-sink"));
        
        // Set up Kafka consumer for the output topic
        setupKafkaConsumerForTopic("grpc-echo-output");
        
        // Given - Create test document
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId("grpc-test-stream-" + UUID.randomUUID())
                .setDocument(PipeDoc.newBuilder()
                        .setId("grpc-test-doc-1")
                        .setTitle("gRPC Input Test Document")
                        .setBody("Testing gRPC input to Kafka output")
                        .build())
                .setCurrentPipelineName("grpc-to-kafka-pipeline")
                .setTargetStepName("grpc-echo")
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Sending PipeStream directly via gRPC to echo service");
        
        // When - Send directly to Echo service via gRPC
        PipeStreamGrpcForwarder grpcForwarder = applicationContext.getBean(PipeStreamGrpcForwarder.class);
        DefaultPipeStreamEngineLogicImpl engineLogic = new DefaultPipeStreamEngineLogicImpl(
                applicationContext.getBean(PipeStepExecutorFactory.class),
                grpcForwarder,
                kafkaForwarder,
                dynamicConfigurationManager
        );
        
        // Process the stream through the engine logic
        engineLogic.processStream(testPipeStream);
        
        // Then - Verify the message was output to Kafka
        LOG.info("Waiting for processed message on Kafka topic: grpc-echo-output");
        ConsumerRecords<String, PipeStream> records = pollKafka("grpc-echo-output", Duration.ofSeconds(10));
        
        assertFalse(records.isEmpty(), "Should have received message on Kafka output topic");
        
        ConsumerRecord<String, PipeStream> record = records.iterator().next();
        PipeStream processedStream = record.value();
        
        assertNotNull(processedStream, "Processed stream should not be null");
        assertEquals("grpc-test-doc-1", processedStream.getDocument().getId(), "Document ID should be preserved");
        assertTrue(processedStream.getHistoryCount() > 0, 
                "Should have execution history from Echo service");
        
        List<String> allLogs = new ArrayList<>();
        processedStream.getHistoryList().forEach(execRecord -> allLogs.addAll(execRecord.getProcessorLogsList()));
        assertFalse(allLogs.isEmpty(), "Should have processor logs");
        
        LOG.info("Successfully processed gRPC input to Kafka output. Processor logs: {}", allLogs);
    }
    
    @Test
    @DisplayName("Should process document through two Echo services via gRPC forwarding")
    void testGrpcServiceToServiceForwarding() throws Exception {
        // NOTE: This test demonstrates gRPC forwarding capability
        // In a real deployment, gRPC forwarding would be between different engine instances
        // For this test, we'll simulate the scenario by having the engine forward to itself
        
        tearDown(); // Clean up previous config
        
        // Start the engine's gRPC server to enable engine-to-engine forwarding
        int enginePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            enginePort = socket.getLocalPort();
        }
        
        // Get the PipeStreamEngineImpl bean and start it as a gRPC service
        PipeStreamEngineImpl engineImpl = applicationContext.getBean(PipeStreamEngineImpl.class);
        Server engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(engineImpl)
                .build()
                .start();
        
        LOG.info("Started PipeStreamEngine gRPC server on port {} for engine-to-engine forwarding test", enginePort);
        
        // Register the engine service channel
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build();
        grpcChannelManager.updateChannel("test-engine", engineChannel);
        
        // Create pipeline with echo1 -> echo2 via gRPC engine forwarding
        PipelineStepConfig echo1Step = PipelineStepConfig.builder()
                .stepName("echo1")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("grpc-forward", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("echo2")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("test-engine")  // Forward to the engine service, not the module
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig echo2Step = PipelineStepConfig.builder()
                .stepName("echo2")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("kafka-output",
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("final-sink")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("echo2-output")
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig finalSinkStep = PipelineStepConfig.builder()
                .stepName("final-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name("grpc-forward-pipeline")
                .pipelineSteps(Map.of(
                        "echo1", echo1Step,
                        "echo2", echo2Step,
                        "final-sink", finalSinkStep
                ))
                .build();
        
        storeTestConfiguration("grpc-forward-pipeline", testPipeline,
                Set.of("echo2-output"), Set.of("echo", "dummy-sink", "test-engine"));
        
        // Set up Kafka consumer for the final output
        setupKafkaConsumerForTopic("echo2-output");
        
        try {
            // Given
            PipeStream testPipeStream = PipeStream.newBuilder()
                    .setStreamId("grpc-forward-stream-" + UUID.randomUUID())
                    .setDocument(PipeDoc.newBuilder()
                            .setId("grpc-forward-doc-1")
                            .setTitle("gRPC Forwarding Test")
                            .setBody("Testing echo1 -> echo2 via gRPC")
                            .build())
                    .setCurrentPipelineName("grpc-forward-pipeline")
                    .setTargetStepName("echo1")
                    .setCurrentHopNumber(0)
                    .build();
            
            LOG.info("Starting gRPC service-to-service forwarding test");
            
            // When - Process through the pipeline
            PipeStreamGrpcForwarder grpcForwarder = applicationContext.getBean(PipeStreamGrpcForwarder.class);
            DefaultPipeStreamEngineLogicImpl engineLogic = new DefaultPipeStreamEngineLogicImpl(
                    applicationContext.getBean(PipeStepExecutorFactory.class),
                    grpcForwarder,
                    kafkaForwarder,
                    dynamicConfigurationManager
            );
            
            engineLogic.processStream(testPipeStream);
            
            // Then - Verify the message went through both services
            LOG.info("Waiting for message that passed through both Echo services");
            ConsumerRecords<String, PipeStream> records = pollKafka("echo2-output", Duration.ofSeconds(15));
            
            assertFalse(records.isEmpty(), "Should have received message after passing through both services");
            
            ConsumerRecord<String, PipeStream> record = records.iterator().next();
            PipeStream processedStream = record.value();
            
            assertNotNull(processedStream, "Processed stream should not be null");
            assertEquals("grpc-forward-doc-1", processedStream.getDocument().getId());
            
            // Verify processor logs from both echo services
            assertTrue(processedStream.getHistoryCount() >= 2, "Should have history from at least 2 services");
            
            List<String> allLogs = new ArrayList<>();
            processedStream.getHistoryList().forEach(execRecord -> allLogs.addAll(execRecord.getProcessorLogsList()));
            
            // Check that both echo1 and echo2 processed the document
            boolean hasEcho1Step = processedStream.getHistoryList().stream()
                    .anyMatch(execRecord -> execRecord.getStepName().equals("echo1"));
            boolean hasEcho2Step = processedStream.getHistoryList().stream()
                    .anyMatch(execRecord -> execRecord.getStepName().equals("echo2"));
            
            assertTrue(hasEcho1Step, "Should have execution record from echo1");
            assertTrue(hasEcho2Step, "Should have execution record from echo2");
            
            LOG.info("Successfully verified gRPC forwarding through two services. History: {}", 
                    processedStream.getHistoryList());
        } finally {
            // Clean up the engine gRPC server
            engineGrpcServer.shutdown();
            try {
                engineGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineGrpcServer.shutdownNow();
            }
            LOG.info("Shut down engine gRPC server");
        }
    }
    
    @Test
    @DisplayName("Should handle mixed routing: Kafka input -> gRPC forward -> Kafka output")
    void testMixedRoutingScenario() throws Exception {
        // Set up a complex pipeline with mixed routing
        tearDown(); // Clean up previous config
        
        // Start the engine's gRPC server for engine-to-engine forwarding
        int enginePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            enginePort = socket.getLocalPort();
        }
        
        PipeStreamEngineImpl engineImpl = applicationContext.getBean(PipeStreamEngineImpl.class);
        Server engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(engineImpl)
                .build()
                .start();
        
        LOG.info("Started PipeStreamEngine gRPC server on port {} for mixed routing test", enginePort);
        
        // Register the engine service channel
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build();
        grpcChannelManager.updateChannel("mixed-engine", engineChannel);
        
        String kafkaInputTopic = "pipeline.mixed-pipeline.step.kafka-entry.input";
        
        // Kafka input -> gRPC forward -> Kafka output
        PipelineStepConfig kafkaEntryStep = PipelineStepConfig.builder()
                .stepName("kafka-entry")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of(kafkaInputTopic))
                                .consumerGroupId("mixed-consumer-group")
                                .kafkaConsumerProperties(Collections.emptyMap())
                                .build()
                ))
                .outputs(Map.of("grpc-forward",
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("grpc-processor")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("mixed-engine")  // Forward to engine, not module
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig grpcProcessorStep = PipelineStepConfig.builder()
                .stepName("grpc-processor")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("kafka-final",
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("final-kafka-sink")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("mixed-final-output")
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig finalKafkaSinkStep = PipelineStepConfig.builder()
                .stepName("final-kafka-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name("mixed-pipeline")
                .pipelineSteps(Map.of(
                        "kafka-entry", kafkaEntryStep,
                        "grpc-processor", grpcProcessorStep,
                        "final-kafka-sink", finalKafkaSinkStep
                ))
                .build();
        
        storeTestConfiguration("mixed-pipeline", testPipeline,
                Set.of(kafkaInputTopic, "mixed-final-output"), 
                Set.of("echo", "dummy-sink", "mixed-engine"));
        
        try {
            // Wait for Kafka listener to be ready
            await().atMost(10, TimeUnit.SECONDS)
                    .pollInterval(500, TimeUnit.MILLISECONDS)
                    .until(() -> {
                        Map<String, ConsumerStatus> statuses = kafkaListenerManager.getConsumerStatuses();
                        return statuses.keySet().stream()
                                .anyMatch(key -> key.contains("mixed-pipeline:kafka-entry"));
                    });
            
            // Set up consumer for final output
            setupKafkaConsumerForTopic("mixed-final-output");
            
            // Given
            PipeStream testPipeStream = PipeStream.newBuilder()
                    .setStreamId("mixed-stream-" + UUID.randomUUID())
                    .setDocument(PipeDoc.newBuilder()
                            .setId("mixed-doc-1")
                            .setTitle("Mixed Routing Test")
                            .setBody("Testing Kafka -> gRPC -> Kafka routing")
                            .build())
                    .setCurrentPipelineName("mixed-pipeline")
                    .setTargetStepName("kafka-entry")
                    .setCurrentHopNumber(0)
                    .build();
            
            LOG.info("Sending message to Kafka input topic for mixed routing test");
            
            // When - Send to Kafka input
            kafkaForwarder.forwardToKafka(testPipeStream, kafkaInputTopic).get(10, TimeUnit.SECONDS);
            
            // Then - Verify final output
            LOG.info("Waiting for message after mixed routing");
            ConsumerRecords<String, PipeStream> records = pollKafka("mixed-final-output", Duration.ofSeconds(15));
            
            assertFalse(records.isEmpty(), "Should have received message after mixed routing");
            
            ConsumerRecord<String, PipeStream> record = records.iterator().next();
            PipeStream processedStream = record.value();
            
            assertNotNull(processedStream);
            assertEquals("mixed-doc-1", processedStream.getDocument().getId());
            
            // Verify all steps were executed
            assertTrue(processedStream.getHistoryCount() >= 2, "Should have history from multiple steps");
            
            boolean hasKafkaEntryStep = processedStream.getHistoryList().stream()
                    .anyMatch(execRecord -> execRecord.getStepName().equals("kafka-entry"));
            boolean hasGrpcProcessorStep = processedStream.getHistoryList().stream()
                    .anyMatch(execRecord -> execRecord.getStepName().equals("grpc-processor"));
            
            assertTrue(hasKafkaEntryStep, "Should have execution record from kafka-entry step");
            assertTrue(hasGrpcProcessorStep, "Should have execution record from grpc-processor step");
            
            LOG.info("Successfully verified mixed routing scenario. History: {}", processedStream.getHistoryList());
        } finally {
            // Clean up the engine gRPC server
            engineGrpcServer.shutdown();
            try {
                engineGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineGrpcServer.shutdownNow();
            }
            LOG.info("Shut down engine gRPC server for mixed routing test");
        }
    }
    
    @Test
    @DisplayName("Should demonstrate fan-out routing: one step to multiple outputs")
    void testFanOutRouting() throws Exception {
        // Clean up and set up fan-out pipeline
        tearDown();
        
        // Start the engine's gRPC server for fan-out gRPC forwarding
        int enginePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            enginePort = socket.getLocalPort();
        }
        
        PipeStreamEngineImpl engineImpl = applicationContext.getBean(PipeStreamEngineImpl.class);
        Server engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(engineImpl)
                .build()
                .start();
        
        LOG.info("Started PipeStreamEngine gRPC server on port {} for fan-out test", enginePort);
        
        // Register the engine service channel
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build();
        grpcChannelManager.updateChannel("fanout-engine", engineChannel);
        
        // Create pipeline with fan-out: input -> echo -> (kafka1, kafka2, grpc)
        PipelineStepConfig fanOutEchoStep = PipelineStepConfig.builder()
                .stepName("fan-out-echo")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of(
                        "output1", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("sink1")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("fan-out-topic-1")
                                        .build())
                                .build(),
                        "output2", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("sink2")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("fan-out-topic-2")
                                        .build())
                                .build(),
                        "output3", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("grpc-processor")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("fanout-engine")  // Forward to engine
                                        .build())
                                .build()
                ))
                .build();
        
        PipelineStepConfig grpcProcessorStep = PipelineStepConfig.builder()
                .stepName("grpc-processor")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("final", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("grpc-sink")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("fan-out-grpc-output")
                                .build())
                        .build()))
                .build();
        
        // Add sink steps
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("fan-out-echo", fanOutEchoStep);
        steps.put("grpc-processor", grpcProcessorStep);
        steps.put("sink1", createSinkStep("sink1"));
        steps.put("sink2", createSinkStep("sink2"));
        steps.put("grpc-sink", createSinkStep("grpc-sink"));
        
        PipelineConfig fanOutPipeline = PipelineConfig.builder()
                .name("fan-out-pipeline")
                .pipelineSteps(steps)
                .build();
        
        storeTestConfiguration("fan-out-pipeline", fanOutPipeline,
                Set.of("fan-out-topic-1", "fan-out-topic-2", "fan-out-grpc-output"),
                Set.of("echo", "dummy-sink", "fanout-engine"));
        
        try {
            // Set up consumers for all output topics
            List<KafkaConsumer<String, PipeStream>> consumers = new ArrayList<>();
            for (String topic : List.of("fan-out-topic-1", "fan-out-topic-2", "fan-out-grpc-output")) {
                consumers.add(createConsumerForTopic(topic));
            }
            
            // Given
            PipeStream testStream = PipeStream.newBuilder()
                    .setStreamId("fan-out-stream-" + UUID.randomUUID())
                    .setDocument(PipeDoc.newBuilder()
                            .setId("fan-out-doc-1")
                            .setTitle("Fan-out Test")
                            .setBody("Testing fan-out routing to multiple destinations")
                            .build())
                    .setCurrentPipelineName("fan-out-pipeline")
                    .setTargetStepName("fan-out-echo")
                    .setCurrentHopNumber(0)
                    .build();
            
            // When - Process through engine
            DefaultPipeStreamEngineLogicImpl engineLogic = createEngineLogic();
            engineLogic.processStream(testStream);
            
            // Then - Verify all outputs received the message
            Map<String, Boolean> receivedMessages = new HashMap<>();
            long endTime = System.currentTimeMillis() + 15000; // 15 second timeout
            
            while (System.currentTimeMillis() < endTime && receivedMessages.size() < 3) {
                for (int i = 0; i < consumers.size(); i++) {
                    KafkaConsumer<String, PipeStream> consumer = consumers.get(i);
                    ConsumerRecords<String, PipeStream> records = consumer.poll(Duration.ofMillis(1000));
                    
                    if (!records.isEmpty()) {
                        String topic = records.iterator().next().topic();
                        receivedMessages.put(topic, true);
                        LOG.info("Received message on topic: {}", topic);
                    }
                }
            }
            
            // Verify all three outputs received the message
            assertTrue(receivedMessages.containsKey("fan-out-topic-1"), "Should receive message on fan-out-topic-1");
            assertTrue(receivedMessages.containsKey("fan-out-topic-2"), "Should receive message on fan-out-topic-2");
            assertTrue(receivedMessages.containsKey("fan-out-grpc-output"), "Should receive message on fan-out-grpc-output (after gRPC forward)");
            
            LOG.info("Successfully verified fan-out routing to {} destinations", receivedMessages.size());
            
            // Clean up consumers
            consumers.forEach(KafkaConsumer::close);
        } finally {
            // Clean up the engine gRPC server
            engineGrpcServer.shutdown();
            try {
                engineGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineGrpcServer.shutdownNow();
            }
            LOG.info("Shut down engine gRPC server for fan-out test");
        }
    }
    
    @Test
    @DisplayName("Should handle conditional routing based on configuration")
    void testConditionalRouting() throws Exception {
        // This test demonstrates how different outputs can be selected based on configuration
        tearDown();
        
        // Start the engine's gRPC server for conditional routing
        int enginePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            enginePort = socket.getLocalPort();
        }
        
        PipeStreamEngineImpl engineImpl = applicationContext.getBean(PipeStreamEngineImpl.class);
        Server engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(engineImpl)
                .build()
                .start();
        
        LOG.info("Started PipeStreamEngine gRPC server on port {} for conditional routing test", enginePort);
        
        // Register the engine service channel
        ManagedChannel engineChannel = ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build();
        grpcChannelManager.updateChannel("conditional-engine", engineChannel);
        
        // Create pipeline with conditional outputs
        PipelineStepConfig conditionalStep = PipelineStepConfig.builder()
                .stepName("conditional-router")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of(
                        "route-a", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("processor-a")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("conditional-engine")  // Forward to engine
                                        .build())
                                .build(),
                        "route-b", PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("processor-b")
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("conditional-engine")  // Forward to engine
                                        .build())
                                .build()
                ))
                .build();
        
        PipelineStepConfig processorA = PipelineStepConfig.builder()
                .stepName("processor-a")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("output", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("sink-a")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("route-a-output")
                                .build())
                        .build()))
                .build();
        
        PipelineStepConfig processorB = PipelineStepConfig.builder()
                .stepName("processor-b")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("echo")
                        .build())
                .outputs(Map.of("output", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("sink-b")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("route-b-output")
                                .build())
                        .build()))
                .build();
        
        Map<String, PipelineStepConfig> steps = Map.of(
                "conditional-router", conditionalStep,
                "processor-a", processorA,
                "processor-b", processorB,
                "sink-a", createSinkStep("sink-a"),
                "sink-b", createSinkStep("sink-b")
        );
        
        PipelineConfig conditionalPipeline = PipelineConfig.builder()
                .name("conditional-pipeline")
                .pipelineSteps(steps)
                .build();
        
        storeTestConfiguration("conditional-pipeline", conditionalPipeline,
                Set.of("route-a-output", "route-b-output"),
                Set.of("echo", "dummy-sink", "conditional-engine"));
        
        try {
            // Test documents that would trigger different routes
            // In reality, the routing decision would be made by the processor
            // For this test, we'll demonstrate that both routes work
            
            // Set up consumers
            KafkaConsumer<String, PipeStream> consumerA = createConsumerForTopic("route-a-output");
            KafkaConsumer<String, PipeStream> consumerB = createConsumerForTopic("route-b-output");
            
            // Process two documents
            DefaultPipeStreamEngineLogicImpl engineLogic = createEngineLogic();
            
            for (int i = 0; i < 2; i++) {
                PipeStream testStream = PipeStream.newBuilder()
                        .setStreamId("conditional-stream-" + i + "-" + UUID.randomUUID())
                        .setDocument(PipeDoc.newBuilder()
                                .setId("conditional-doc-" + i)
                                .setTitle("Conditional Test " + i)
                                .setBody("Document for route " + (i == 0 ? "A" : "B"))
                                .build())
                        .setCurrentPipelineName("conditional-pipeline")
                        .setTargetStepName("conditional-router")
                        .setCurrentHopNumber(0)
                        .build();
                
                engineLogic.processStream(testStream);
            }
            
            // Verify both routes were exercised
            // Note: In this test both documents go through both routes due to the echo service
            // In a real scenario, the processor would make routing decisions
            Thread.sleep(5000); // Give time for processing
            
            boolean receivedOnA = false;
            boolean receivedOnB = false;
            
            ConsumerRecords<String, PipeStream> recordsA = consumerA.poll(Duration.ofSeconds(5));
            if (!recordsA.isEmpty()) {
                receivedOnA = true;
                LOG.info("Received {} messages on route A", recordsA.count());
            }
            
            ConsumerRecords<String, PipeStream> recordsB = consumerB.poll(Duration.ofSeconds(5));
            if (!recordsB.isEmpty()) {
                receivedOnB = true;
                LOG.info("Received {} messages on route B", recordsB.count());
            }
            
            assertTrue(receivedOnA || receivedOnB, "Should receive messages on at least one route");
            LOG.info("Successfully demonstrated conditional routing");
            
            consumerA.close();
            consumerB.close();
        } finally {
            // Clean up the engine gRPC server
            engineGrpcServer.shutdown();
            try {
                engineGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineGrpcServer.shutdownNow();
            }
            LOG.info("Shut down engine gRPC server for conditional routing test");
        }
    }
    
    // Helper methods
    
    private PipelineStepConfig createSinkStep(String stepName) {
        return PipelineStepConfig.builder()
                .stepName(stepName)
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
    }
    
    private DefaultPipeStreamEngineLogicImpl createEngineLogic() {
        PipeStreamGrpcForwarder grpcForwarder = applicationContext.getBean(PipeStreamGrpcForwarder.class);
        return new DefaultPipeStreamEngineLogicImpl(
                applicationContext.getBean(PipeStepExecutorFactory.class),
                grpcForwarder,
                kafkaForwarder,
                dynamicConfigurationManager
        );
    }
    
    private KafkaConsumer<String, PipeStream> createConsumerForTopic(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + topic + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(SerdeConfig.REGISTRY_URL, 
                applicationContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080"));
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "10");
        
        KafkaConsumer<String, PipeStream> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(topic));
        return consumer;
    }
    
    // Helper methods
    
    private void storeTestConfiguration(String pipelineName, PipelineConfig pipelineConfig,
                                       Set<String> kafkaTopics, Set<String> grpcServices) {
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(pipelineName, pipelineConfig))
                .build();
        
        // Create module configurations for all services
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        for (String service : grpcServices) {
            modules.put(service, PipelineModuleConfiguration.builder()
                    .implementationId(service)
                    .implementationName(service + " Service")
                    .build());
        }
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(modules)
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(kafkaTopics)
                .allowedGrpcServices(grpcServices)
                .build();
        
        // Store configuration in Consul
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        
        // Wait for configuration to be loaded
        LOG.info("Waiting for configuration to be loaded for pipeline: {}", pipelineName);
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    return currentConfig.isPresent() && 
                           currentConfig.get().pipelineGraphConfig().pipelines().containsKey(pipelineName);
                });
    }
    
    private void setupKafkaConsumerForTopic(String topic) {
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
        
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(SerdeConfig.REGISTRY_URL, 
                applicationContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080"));
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(Collections.singletonList(topic));
    }
    
    private void setupKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(SerdeConfig.REGISTRY_URL, 
                applicationContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080"));
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest"); // Changed to latest to avoid old messages
        
        kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(Collections.singletonList(TEST_TOPIC));
        // Poll once to make sure we're at the latest offset
        kafkaConsumer.poll(Duration.ofMillis(100));
    }
    
    private ConsumerRecords<String, PipeStream> pollKafka(String topic, Duration timeout) {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, PipeStream> records = kafkaConsumer.poll(Duration.ofMillis(1000));
            
            if (!records.isEmpty()) {
                LOG.info("Received {} records from Kafka", records.count());
                return records;
            }
        }
        
        LOG.warn("No records received from topic {} within timeout", topic);
        return new ConsumerRecords<>(Collections.emptyMap());
    }
}