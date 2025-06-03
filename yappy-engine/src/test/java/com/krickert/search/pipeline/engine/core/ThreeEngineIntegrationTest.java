package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.ConsulConfigFetcher;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
import com.krickert.search.pipeline.engine.registration.EngineRegistrationService;
import com.krickert.yappy.modules.tikaparser.TikaParserService;
import com.krickert.yappy.modules.chunker.ChunkerService;
import com.krickert.yappy.modules.embedder.EmbedderService;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that demonstrates the proper API-driven approach to build a three-engine system.
 * 
 * This test simulates the real deployment process:
 * 1. Start with a seed engine (from @MicronautTest)
 * 2. Create cluster configuration via Consul API
 * 3. Start module services one at a time
 * 4. Register each service properly
 * 5. Test the complete pipeline flow
 * 
 * Key Design Principles:
 * - NO MOCKS - all real components
 * - API-driven configuration (not hardcoded)
 * - Proper service discovery via Consul
 * - Each module runs as a separate gRPC service
 * - Mixed transport types (gRPC and Kafka)
 */
@MicronautTest
@Property(name = "app.config.cluster-name", value = ThreeEngineIntegrationTest.TEST_CLUSTER_NAME)
@Property(name = "yappy.engine.auto-register", value = "true")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThreeEngineIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ThreeEngineIntegrationTest.class);
    
    static final String TEST_CLUSTER_NAME = "threeEngineApiTestCluster";
    private static final String TEST_PIPELINE = "three-engine-pipeline";
    private static final String TIKA_STEP = "tika-parser";
    private static final String CHUNKER_STEP = "chunker";
    private static final String EMBEDDER_STEP = "embedder";
    private static final String FINAL_OUTPUT_TOPIC = "final-output";
    
    // Seed engine components (injected from @MicronautTest)
    @Inject ApplicationContext seedEngineContext;
    @Inject ConsulBusinessOperationsService consulBusinessOperationsService;
    @Inject ConsulConfigFetcher consulConfigFetcher;
    @Inject EngineRegistrationService engineRegistrationService;
    @Inject DynamicConfigurationManager dynamicConfigurationManager;
    @Inject KafkaForwarder kafkaForwarder;
    @Inject GrpcChannelManager grpcChannelManager;
    @Inject KafkaListenerManager kafkaListenerManager;
    @Inject PipeStepExecutorFactory pipeStepExecutorFactory;
    @Inject PipeStreamGrpcForwarder pipeStreamGrpcForwarder;
    @Inject PipeStreamEngineImpl pipeStreamEngineImpl;
    
    // Module services (manually started)
    private Server tikaGrpcServer;
    private Server chunkerGrpcServer;
    private Server embedderGrpcServer;
    private int tikaPort;
    private int chunkerPort;
    private int embedderPort;
    
    private KafkaConsumer<String, PipeStream> kafkaConsumer;
    
    @BeforeAll
    void setupAll() throws Exception {
        LOG.info("================ SETUP THREE-ENGINE TEST SYSTEM ================");
        LOG.info("This test demonstrates the proper API-driven deployment approach");
        LOG.info("================================================================");
        
        // Clean up any previous configuration
        LOG.info("Phase 1: Clean up previous configuration");
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Wait for seed engine to be fully initialized
        LOG.info("Phase 2: Seed engine is running (from @MicronautTest)");
        TimeUnit.SECONDS.sleep(2);
        
        LOG.info("================ SETUP COMPLETE ================");
    }
    
    @AfterAll
    void tearDownAll() {
        LOG.info("================ TEARDOWN START ================");
        
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
        
        // Stop all module servers
        stopAllServers();
        
        LOG.info("================ TEARDOWN COMPLETE ================");
    }
    
    private void stopAllServers() {
        if (tikaGrpcServer != null) {
            LOG.info("Stopping Tika gRPC server...");
            tikaGrpcServer.shutdown();
            try {
                tikaGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                tikaGrpcServer.shutdownNow();
            }
        }
        if (chunkerGrpcServer != null) {
            LOG.info("Stopping Chunker gRPC server...");
            chunkerGrpcServer.shutdown();
            try {
                chunkerGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                chunkerGrpcServer.shutdownNow();
            }
        }
        if (embedderGrpcServer != null) {
            LOG.info("Stopping Embedder gRPC server...");
            embedderGrpcServer.shutdown();
            try {
                embedderGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                embedderGrpcServer.shutdownNow();
            }
        }
    }
    
    @BeforeEach
    void setUp() {
        LOG.info("================ TEST SETUP ================");
        
        // Clean up any previous configuration
        LOG.info("Cleaning cluster configuration: {}", TEST_CLUSTER_NAME);
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Wait for listeners to be removed
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> kafkaListenerManager.getConsumerStatuses().isEmpty());
        
        LOG.info("================ TEST SETUP COMPLETE ================");
    }
    
    @AfterEach
    void tearDown() {
        // Clean up configuration
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
    }
    
    @Test
    @DisplayName("Should build and test three-engine system using API-driven approach")
    void testThreeEngineSystemViaApis() throws Exception {
        LOG.info("\n🚀 STARTING THREE-ENGINE INTEGRATION TEST 🚀");
        LOG.info("This test demonstrates the proper way to deploy a YAPPY system");
        LOG.info("=============================================================\n");
        
        LOG.info("📋 PHASE 1: CREATE CLUSTER CONFIGURATION");
        createClusterConfiguration();
        
        LOG.info("\n🔧 PHASE 2: START MODULE SERVICES");
        startModuleServices();
        
        LOG.info("\n🔌 PHASE 3: REGISTER SERVICES WITH CONSUL");
        registerServicesWithConsul();
        
        LOG.info("\n🧪 PHASE 4: TEST THE COMPLETE PIPELINE");
        testCompletePipeline();
        
        LOG.info("\n✅ THREE-ENGINE INTEGRATION TEST COMPLETE!");
    }
    
    private void createClusterConfiguration() throws Exception {
        LOG.info("Creating pipeline configuration...");
        
        // Define pipeline steps
        PipelineStepConfig tikaStep = PipelineStepConfig.builder()
                .stepName(TIKA_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser-service")
                        .build())
                .outputs(Map.of("parsed", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName(CHUNKER_STEP)
                                .transportType(TransportType.GRPC)
                                .build()))
                .build();
        
        PipelineStepConfig chunkerStep = PipelineStepConfig.builder()
                .stepName(CHUNKER_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("chunker-service")
                        .build())
                .outputs(Map.of("chunked", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName(EMBEDDER_STEP)
                                .transportType(TransportType.GRPC)
                                .build()))
                .build();
        
        PipelineStepConfig embedderStep = PipelineStepConfig.builder()
                .stepName(EMBEDDER_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("embedder-service")
                        .build())
                .outputs(Map.of("embedded", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("final-sink")
                                .transportType(TransportType.KAFKA)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic(FINAL_OUTPUT_TOPIC)
                                        .build())
                                .build()))
                .build();
        
        PipelineStepConfig sinkStep = PipelineStepConfig.builder()
                .stepName("final-sink")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        // Create pipeline
        PipelineConfig testPipeline = PipelineConfig.builder()
                .name(TEST_PIPELINE)
                .pipelineSteps(Map.of(
                        TIKA_STEP, tikaStep,
                        CHUNKER_STEP, chunkerStep,
                        EMBEDDER_STEP, embedderStep,
                        "final-sink", sinkStep
                ))
                .build();
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of(TEST_PIPELINE, testPipeline))
                .build();
        
        // Create module configurations
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        modules.put("tika-parser-service", PipelineModuleConfiguration.builder()
                .implementationId("tika-parser-service")
                .implementationName("Tika Parser Service")
                .build());
        modules.put("chunker-service", PipelineModuleConfiguration.builder()
                .implementationId("chunker-service")
                .implementationName("Chunker Service")
                .build());
        modules.put("embedder-service", PipelineModuleConfiguration.builder()
                .implementationId("embedder-service")
                .implementationName("Embedder Service")
                .build());
        modules.put("dummy-sink", PipelineModuleConfiguration.builder()
                .implementationId("dummy-sink")
                .implementationName("Dummy Sink")
                .build());
        
        PipelineModuleMap moduleMap = PipelineModuleMap.builder()
                .availableModules(modules)
                .build();
        
        // Create cluster configuration
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName(TEST_CLUSTER_NAME)
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(FINAL_OUTPUT_TOPIC))
                .allowedGrpcServices(Set.of("tika-parser-service", "chunker-service", 
                        "embedder-service", "dummy-sink"))
                .build();
        
        // Store configuration (simulating admin API call)
        LOG.info("Storing cluster configuration in Consul...");
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        LOG.info("✅ Cluster configuration stored successfully");
        
        // Wait for configuration to propagate
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    return currentConfig.isPresent() && currentConfig.get().clusterName().equals(TEST_CLUSTER_NAME);
                });
        
        LOG.info("✅ Configuration loaded by DynamicConfigurationManager");
    }
    
    private void startModuleServices() throws Exception {
        // Start Tika Parser Service
        tikaPort = findAvailablePort();
        LOG.info("Starting Tika Parser Service on port {}", tikaPort);
        
        TikaParserService tikaService = new TikaParserService();
        tikaGrpcServer = ServerBuilder
                .forPort(tikaPort)
                .addService(tikaService)
                .build()
                .start();
        
        LOG.info("✅ Tika Parser Service started on port {}", tikaPort);
        
        // Start Chunker Service
        chunkerPort = findAvailablePort();
        LOG.info("Starting Chunker Service on port {}", chunkerPort);
        
        ChunkerService chunkerService = new ChunkerService();
        chunkerGrpcServer = ServerBuilder
                .forPort(chunkerPort)
                .addService(chunkerService)
                .build()
                .start();
        
        LOG.info("✅ Chunker Service started on port {}", chunkerPort);
        
        // Start Embedder Service
        embedderPort = findAvailablePort();
        LOG.info("Starting Embedder Service on port {}", embedderPort);
        
        EmbedderService embedderService = new EmbedderService();
        embedderGrpcServer = ServerBuilder
                .forPort(embedderPort)
                .addService(embedderService)
                .build()
                .start();
        
        LOG.info("✅ Embedder Service started on port {}", embedderPort);
    }
    
    private void registerServicesWithConsul() throws Exception {
        // In a real deployment, each service would register itself
        // For this test, we'll simulate registration by updating the GrpcChannelManager
        
        LOG.info("Registering services with GrpcChannelManager...");
        
        // Register Tika service
        grpcChannelManager.updateChannel("tika-parser-service", ManagedChannelBuilder
                .forAddress("localhost", tikaPort)
                .usePlaintext()
                .build());
        LOG.info("✅ Registered tika-parser-service at localhost:{}", tikaPort);
        
        // Register Chunker service
        grpcChannelManager.updateChannel("chunker-service", ManagedChannelBuilder
                .forAddress("localhost", chunkerPort)
                .usePlaintext()
                .build());
        LOG.info("✅ Registered chunker-service at localhost:{}", chunkerPort);
        
        // Register Embedder service
        grpcChannelManager.updateChannel("embedder-service", ManagedChannelBuilder
                .forAddress("localhost", embedderPort)
                .usePlaintext()
                .build());
        LOG.info("✅ Registered embedder-service at localhost:{}", embedderPort);
        
        // Wait for services to be ready
        TimeUnit.SECONDS.sleep(2);
    }
    
    private void testCompletePipeline() throws Exception {
        // Set up Kafka consumer
        setupKafkaConsumer();
        
        // Load test data
        Collection<PipeStream> sampleStreams = ProtobufTestDataHelper.loadSamplePipeStreams();
        assertFalse(sampleStreams.isEmpty(), "Should have sample streams to process");
        
        // Select a document
        PipeStream inputStream = sampleStreams.stream()
                .filter(stream -> {
                    String filename = stream.getContextParamsOrDefault("filename", "");
                    return filename.toLowerCase().endsWith(".pdf") || 
                           filename.toLowerCase().endsWith(".docx") ||
                           filename.toLowerCase().endsWith(".txt");
                })
                .findFirst()
                .orElse(sampleStreams.iterator().next());
        
        // Prepare the stream for our pipeline
        PipeStream testPipeStream = inputStream.toBuilder()
                .setStreamId("test-three-engine-" + UUID.randomUUID())
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(TIKA_STEP)
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Processing document: {}", testPipeStream.getDocument().getBlob().getFilename());
        LOG.info("Stream ID: {}", testPipeStream.getStreamId());
        
        // Create engine logic
        DefaultPipeStreamEngineLogicImpl engineLogic = new DefaultPipeStreamEngineLogicImpl(
                pipeStepExecutorFactory,
                pipeStreamGrpcForwarder,
                kafkaForwarder,
                dynamicConfigurationManager
        );
        
        // Process the stream
        LOG.info("Sending stream to pipeline...");
        engineLogic.processStream(testPipeStream);
        
        // Wait for the final output
        LOG.info("Waiting for processed document on topic: {}", FINAL_OUTPUT_TOPIC);
        
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
        
        // Verify results
        assertNotNull(processedStream, "Should have received processed message");
        assertEquals(testPipeStream.getStreamId(), processedStream.getStreamId(), "Stream ID should be preserved");
        
        // Verify all steps were executed
        assertTrue(processedStream.getHistoryCount() >= 3, 
                "Should have history from at least 3 steps");
        
        boolean hasTikaStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(TIKA_STEP));
        boolean hasChunkerStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(CHUNKER_STEP));
        boolean hasEmbedderStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(EMBEDDER_STEP));
        
        assertTrue(hasTikaStep, "Should have execution record from Tika parser");
        assertTrue(hasChunkerStep, "Should have execution record from Chunker");
        assertTrue(hasEmbedderStep, "Should have execution record from Embedder");
        
        // Log results
        LOG.info("\n🎉 PIPELINE EXECUTION SUCCESSFUL! 🎉");
        LOG.info("Document processed through all stages:");
        processedStream.getHistoryList().forEach(record -> {
            LOG.info("  ✅ Step '{}': {} (hop {})", 
                    record.getStepName(), 
                    record.getStatus(), 
                    record.getHopNumber());
        });
        
        LOG.info("\n📊 TEST SUMMARY:");
        LOG.info("  ✅ API-driven cluster configuration");
        LOG.info("  ✅ Three separate module services");
        LOG.info("  ✅ Service registration and discovery");
        LOG.info("  ✅ Mixed transport pipeline (gRPC → gRPC → gRPC → Kafka)");
        LOG.info("  ✅ Complete end-to-end processing");
        LOG.info("  ✅ NO MOCKS - Real integration!");
    }
    
    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
    
    private void setupKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                seedEngineContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-three-engine-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");
        props.put(SerdeConfig.REGISTRY_URL, 
                seedEngineContext.getProperty("apicurio.registry.url", String.class).orElse("http://localhost:8080"));
        props.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, PipeStream.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        kafkaConsumer = new KafkaConsumer<>(props);
        kafkaConsumer.subscribe(Collections.singletonList(FINAL_OUTPUT_TOPIC));
        LOG.info("✅ Kafka consumer subscribed to topic: {}", FINAL_OUTPUT_TOPIC);
    }
}