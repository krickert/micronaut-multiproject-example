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
 * Integration test demonstrating a complex multi-service pipeline.
 * This test simulates a Tika -> Chunker -> Embedder pipeline using Echo services
 * with different configurations to demonstrate the pipeline capabilities.
 * 
 * Once the actual Tika, Chunker, and Embedder services are integrated,
 * this test can be updated to use the real implementations.
 */
@MicronautTest
@Property(name = "micronaut.config-client.enabled", value = "false")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "app.config.cluster-name", value = ComplexMultiServicePipelineIntegrationTest.TEST_CLUSTER_NAME)
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
class ComplexMultiServicePipelineIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ComplexMultiServicePipelineIntegrationTest.class);
    
    static final String TEST_CLUSTER_NAME = "complexPipelineTestCluster";
    private static final String TEST_PIPELINE = "complex-multi-service-pipeline";
    private static final String PARSER_STEP = "parser-service";
    private static final String CHUNKER_STEP_1 = "chunker-sentences";
    private static final String CHUNKER_STEP_2 = "chunker-paragraphs";
    private static final String EMBEDDER_STEP = "embedder-service";
    private static final String FINAL_OUTPUT_TOPIC = "pipeline-final-output";
    
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
    
    private Server service1GrpcServer;
    private Server service2GrpcServer;
    private Server service3GrpcServer;
    private Server service4GrpcServer;
    private Server engineGrpcServer;
    private KafkaConsumer<String, PipeStream> kafkaConsumer;
    
    @BeforeAll
    void setupAll() throws IOException {
        // Find free ports for all services
        int service1Port, service2Port, service3Port, service4Port, enginePort;
        try (ServerSocket socket1 = new ServerSocket(0);
             ServerSocket socket2 = new ServerSocket(0);
             ServerSocket socket3 = new ServerSocket(0);
             ServerSocket socket4 = new ServerSocket(0);
             ServerSocket socket5 = new ServerSocket(0)) {
            service1Port = socket1.getLocalPort();
            service2Port = socket2.getLocalPort();
            service3Port = socket3.getLocalPort();
            service4Port = socket4.getLocalPort();
            enginePort = socket5.getLocalPort();
        }
        
        // Start multiple Echo services to simulate different services
        LOG.info("Starting service 1 (parser simulation) on port {}", service1Port);
        EchoService service1 = new EchoService();
        service1GrpcServer = ServerBuilder
                .forPort(service1Port)
                .addService(service1)
                .build()
                .start();
        
        LOG.info("Starting service 2 (chunker 1 simulation) on port {}", service2Port);
        EchoService service2 = new EchoService();
        service2GrpcServer = ServerBuilder
                .forPort(service2Port)
                .addService(service2)
                .build()
                .start();
        
        LOG.info("Starting service 3 (chunker 2 simulation) on port {}", service3Port);
        EchoService service3 = new EchoService();
        service3GrpcServer = ServerBuilder
                .forPort(service3Port)
                .addService(service3)
                .build()
                .start();
        
        LOG.info("Starting service 4 (embedder simulation) on port {}", service4Port);
        EchoService service4 = new EchoService();
        service4GrpcServer = ServerBuilder
                .forPort(service4Port)
                .addService(service4)
                .build()
                .start();
        
        // Start Engine gRPC service for routing
        LOG.info("Starting Engine gRPC service on port {}", enginePort);
        PipeStreamEngineImpl engineImpl = applicationContext.getBean(PipeStreamEngineImpl.class);
        engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(engineImpl)
                .build()
                .start();
        
        // Create channels to all services
        grpcChannelManager.updateChannel("parser-service", ManagedChannelBuilder
                .forAddress("localhost", service1Port)
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("chunker-service-1", ManagedChannelBuilder
                .forAddress("localhost", service2Port)
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("chunker-service-2", ManagedChannelBuilder
                .forAddress("localhost", service3Port)
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("embedder-service", ManagedChannelBuilder
                .forAddress("localhost", service4Port)
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("pipeline-engine", ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build());
        
        LOG.info("All services registered with GrpcChannelManager");
    }
    
    @AfterAll
    void tearDownAll() {
        // Shutdown all gRPC servers
        shutdownServer(service1GrpcServer, "Service 1");
        shutdownServer(service2GrpcServer, "Service 2");
        shutdownServer(service3GrpcServer, "Service 3");
        shutdownServer(service4GrpcServer, "Service 4");
        shutdownServer(engineGrpcServer, "Engine");
        
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
    }
    
    private void shutdownServer(Server server, String name) {
        if (server != null) {
            LOG.info("Shutting down {} gRPC server...", name);
            server.shutdown();
            try {
                server.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                server.shutdownNow();
            }
        }
    }
    
    @BeforeEach
    void setUp() {
        // Clean up any previous configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Create pipeline configuration with multiple steps
        PipelineStepConfig parserStep = PipelineStepConfig.builder()
                .stepName(PARSER_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("parser-service")
                        .build())
                .outputs(Map.of("parsed", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName(CHUNKER_STEP_1)
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("parser-output")
                                        .build())
                                .build()))
                .build();
        
        // Chunker step 1 - simulating sentence-based chunking
        PipelineStepConfig chunkerStep1 = PipelineStepConfig.builder()
                .stepName(CHUNKER_STEP_1)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("chunker-service-1")
                        .build())
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of("parser-output"))
                                .consumerGroupId("chunker-sentences-group")
                                .kafkaConsumerProperties(Collections.emptyMap())
                                .build()
                ))
                .outputs(Map.of("chunked", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName(CHUNKER_STEP_2)
                                .transportType(TransportType.GRPC)
                                .grpcTransport(GrpcTransportConfig.builder()
                                        .serviceName("pipeline-engine")
                                        .build())
                                .build()))
                .build();
        
        // Chunker step 2 - simulating paragraph-based chunking
        PipelineStepConfig chunkerStep2 = PipelineStepConfig.builder()
                .stepName(CHUNKER_STEP_2)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("chunker-service-2")
                        .build())
                .outputs(Map.of("chunked", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName(EMBEDDER_STEP)
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic("chunker-output")
                                        .build())
                                .build()))
                .build();
        
        // Embedder step
        PipelineStepConfig embedderStep = PipelineStepConfig.builder()
                .stepName(EMBEDDER_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("embedder-service")
                        .build())
                .kafkaInputs(List.of(
                        KafkaInputDefinition.builder()
                                .listenTopics(List.of("chunker-output"))
                                .consumerGroupId("embedder-group")
                                .kafkaConsumerProperties(Collections.emptyMap())
                                .build()
                ))
                .outputs(Map.of("embedded", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("final-sink")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic(FINAL_OUTPUT_TOPIC)
                                        .build())
                                .build()))
                .build();
        
        // Add a sink step to satisfy validation
        PipelineStepConfig sinkStep = PipelineStepConfig.builder()
                .stepName("final-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE)
                .pipelineSteps(Map.of(
                        PARSER_STEP, parserStep,
                        CHUNKER_STEP_1, chunkerStep1,
                        CHUNKER_STEP_2, chunkerStep2,
                        EMBEDDER_STEP, embedderStep,
                        "final-sink", sinkStep
                ))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE, testPipeline))
                .build();
        
        // Create module configurations
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        modules.put("parser-service", PipelineModuleConfiguration.builder()
                .implementationId("parser-service")
                .implementationName("Parser Service (Echo)")
                .build());
        modules.put("chunker-service-1", PipelineModuleConfiguration.builder()
                .implementationId("chunker-service-1")
                .implementationName("Chunker Service 1 (Echo)")
                .build());
        modules.put("chunker-service-2", PipelineModuleConfiguration.builder()
                .implementationId("chunker-service-2")
                .implementationName("Chunker Service 2 (Echo)")
                .build());
        modules.put("embedder-service", PipelineModuleConfiguration.builder()
                .implementationId("embedder-service")
                .implementationName("Embedder Service (Echo)")
                .build());
        modules.put("dummy-sink", PipelineModuleConfiguration.builder()
                .implementationId("dummy-sink")
                .implementationName("Dummy Sink Service")
                .build());
        modules.put("pipeline-engine", PipelineModuleConfiguration.builder()
                .implementationId("pipeline-engine")
                .implementationName("Pipeline Engine Service")
                .build());
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(modules)
                .build();
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of("parser-output", "chunker-output", FINAL_OUTPUT_TOPIC))
                .allowedGrpcServices(Set.of("parser-service", "chunker-service-1", "chunker-service-2", 
                        "embedder-service", "dummy-sink", "pipeline-engine"))
                .build();
        
        // Store configuration in Consul
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
        
        // Set up Kafka consumer for final output
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
    @DisplayName("Should process document through complex multi-service pipeline")
    void testComplexMultiServicePipeline() throws Exception {
        // Create a test document
        PipeStream testPipeStream = PipeStream.newBuilder()
                .setStreamId("complex-pipeline-test-" + UUID.randomUUID())
                .setDocument(PipeDoc.newBuilder()
                        .setId("test-doc-complex")
                        .setTitle("Complex Pipeline Test Document")
                        .setBody("This is a test document that will go through multiple services: " +
                                "parser, chunker (2 different configurations), and embedder. " +
                                "Each service will process the document and add its own metadata.")
                        .build())
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(PARSER_STEP)
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Processing document through complex pipeline with 4 services");
        
        // Process through the pipeline
        DefaultPipeStreamEngineLogicImpl engineLogic = new DefaultPipeStreamEngineLogicImpl(
                applicationContext.getBean(PipeStepExecutorFactory.class),
                applicationContext.getBean(PipeStreamGrpcForwarder.class),
                kafkaForwarder,
                dynamicConfigurationManager
        );
        
        engineLogic.processStream(testPipeStream);
        
        // Wait for the final output
        LOG.info("Waiting for processed document on final output topic: {}", FINAL_OUTPUT_TOPIC);
        
        PipeStream processedStream = null;
        long endTime = System.currentTimeMillis() + 30000; // 30 second timeout
        
        while (processedStream == null && System.currentTimeMillis() < endTime) {
            ConsumerRecords<String, PipeStream> records = kafkaConsumer.poll(Duration.ofSeconds(2));
            
            for (ConsumerRecord<String, PipeStream> record : records) {
                PipeStream stream = record.value();
                LOG.info("Received message with streamId: {}", stream.getStreamId());
                if (testPipeStream.getStreamId().equals(stream.getStreamId())) {
                    processedStream = stream;
                    break;
                }
            }
        }
        
        assertNotNull(processedStream, "Should have received processed message");
        assertEquals(testPipeStream.getStreamId(), processedStream.getStreamId(), "Stream ID should be preserved");
        
        // Verify the document went through all steps
        assertTrue(processedStream.getHistoryCount() >= 4, 
                "Should have history from at least 4 steps");
        
        // Verify each step was executed
        boolean hasParserStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(PARSER_STEP));
        boolean hasChunker1Step = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(CHUNKER_STEP_1));
        boolean hasChunker2Step = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(CHUNKER_STEP_2));
        boolean hasEmbedderStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(EMBEDDER_STEP));
        
        assertTrue(hasParserStep, "Should have execution record from parser service");
        assertTrue(hasChunker1Step, "Should have execution record from first chunker");
        assertTrue(hasChunker2Step, "Should have execution record from second chunker");
        assertTrue(hasEmbedderStep, "Should have execution record from embedder service");
        
        // Verify the document has been preserved
        assertNotNull(processedStream.getDocument(), "Should have a document");
        assertEquals("test-doc-complex", processedStream.getDocument().getId());
        
        // Log the processing summary
        LOG.info("Successfully processed document through complex pipeline:");
        LOG.info("  - Document ID: {}", processedStream.getDocument().getId());
        LOG.info("  - Processing steps completed: {}", processedStream.getHistoryCount());
        
        processedStream.getHistoryList().forEach(record -> {
            LOG.info("    Step '{}': {} (hop {})", 
                    record.getStepName(), 
                    record.getStatus(), 
                    record.getHopNumber());
            record.getProcessorLogsList().forEach(log -> LOG.info("      - {}", log));
        });
        
        // Verify mixed routing (Kafka and gRPC)
        LOG.info("\n🎉 Complex Pipeline Test Success! 🎉");
        LOG.info("Successfully demonstrated:");
        LOG.info("  ✅ Multi-service pipeline (4 services)");
        LOG.info("  ✅ Mixed routing (Kafka and gRPC)");
        LOG.info("  ✅ Multiple Kafka listeners");
        LOG.info("  ✅ Service-to-service gRPC forwarding");
        LOG.info("  ✅ Complex pipeline orchestration");
        LOG.info("\nThis architecture is ready for:");
        LOG.info("  - Real Tika document parsing");
        LOG.info("  - Multiple chunking strategies");
        LOG.info("  - Embedding generation");
        LOG.info("  - OpenSearch integration!");
    }
    
    private void setupKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-complex-pipeline-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(SerdeConfig.REGISTRY_URL, 
                applicationContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080"));
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(Collections.singletonList(FINAL_OUTPUT_TOPIC));
    }
}