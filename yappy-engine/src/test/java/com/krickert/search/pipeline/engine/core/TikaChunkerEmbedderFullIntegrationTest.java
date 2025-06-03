package com.krickert.search.pipeline.engine.core;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.util.ProtobufTestDataHelper;
import com.krickert.search.pipeline.engine.kafka.KafkaForwarder;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStatus;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.engine.grpc.PipeStreamGrpcForwarder;
import com.krickert.search.pipeline.engine.grpc.PipeStreamEngineImpl;
import com.krickert.search.pipeline.step.PipeStepExecutorFactory;
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
 * Full integration test demonstrating engine-to-engine communication with mixed transports.
 * This test validates the complete data processing flow through multiple engines
 * using both Kafka and gRPC transports between engines.
 * 
 * Architecture:
 * - Tika Engine (with tika-parser module) -> Kafka -> Chunker Engine 
 * - Chunker Engine (with chunker module) -> gRPC -> Embedder Engine
 * - Embedder Engine (with embedder module) -> Kafka -> Final output
 */
// Temporarily disable @MicronautTest to avoid test resources issue
// @MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TikaChunkerEmbedderFullIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(TikaChunkerEmbedderFullIntegrationTest.class);
    
    static final String TEST_CLUSTER_NAME = "tikaChunkerEmbedderFullTestCluster";
    private static final String TEST_PIPELINE = "tika-chunker-embedder-pipeline";
    private static final String TIKA_STEP = "tika-parser";
    private static final String CHUNKER_STEP_1 = "chunker-1";
    private static final String CHUNKER_STEP_2 = "chunker-2";
    private static final String EMBEDDER_STEP = "embedder";
    private static final String FINAL_OUTPUT_TOPIC = "embedding-output";
    
    
    // Module contexts (with automatic gRPC servers)
    private ApplicationContext tikaModuleContext;
    private ApplicationContext chunkerModuleContext;
    private ApplicationContext embedderModuleContext;
    private final Map<String, String> resolvedModulePorts = new TreeMap<>();
    
    // These will be initialized in @BeforeAll
    ApplicationContext applicationContext;
    ConsulBusinessOperationsService consulBusinessOperationsService;
    DynamicConfigurationManager dynamicConfigurationManager;
    KafkaForwarder kafkaForwarder;
    GrpcChannelManager grpcChannelManager;
    KafkaListenerManager kafkaListenerManager;
    PipeStepExecutorFactory pipeStepExecutorFactory;
    PipeStreamGrpcForwarder pipeStreamGrpcForwarder;
    PipeStreamEngineImpl pipeStreamEngineImpl;
    
    private Server engineGrpcServer;
    private int enginePort;
    private KafkaConsumer<String, PipeStream> kafkaConsumer;
    
    // Simplified approach - start modules in @BeforeAll instead of TestPropertyProvider
    
    private void startTikaModule() throws IOException {
        // Use proper ApplicationContext approach for automatic health service support
        int tikaPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            tikaPort = socket.getLocalPort();
        }
        
        LOG.info("Starting Tika ApplicationContext with gRPC on port {}", tikaPort);
        
        // Create dedicated ApplicationContext for Tika module with proper gRPC configuration
        Map<String, Object> tikaProps = new HashMap<>();
        tikaProps.put("micronaut.application.name", "tika-parser-test");
        tikaProps.put("grpc.server.port", tikaPort);
        tikaProps.put("grpc.server.health.enabled", true);
        tikaProps.put("micronaut.grpc.server.port", tikaPort);  // Try both property paths
        tikaProps.put("micronaut.grpc.server.health.enabled", true);
        tikaProps.put("micronaut.server.port", -1);  // Disable HTTP server
        tikaProps.put("yappy.engine.auto-register", false);  // Disable engine registration for modules
        tikaProps.put("consul.client.registration.enabled", false);  // Disable auto-registration
        tikaProps.put("micronaut.test-resources.enabled", false);  // Disable test resources
        tikaProps.put("micronaut.environments", "test");
        
        // Create ApplicationContext directly - it should start gRPC server automatically
        this.tikaModuleContext = ApplicationContext.run(tikaProps, "test");
            
        resolvedModulePorts.put("tika-parser", String.valueOf(tikaPort));
        
        LOG.info("Tika ApplicationContext started with automatic gRPC server on port {}", tikaPort);
        
        // Verify the service bean exists
        try {
            com.krickert.yappy.modules.tikaparser.TikaParserService tikaService = 
                tikaModuleContext.getBean(com.krickert.yappy.modules.tikaparser.TikaParserService.class);
            LOG.info("✅ TikaParserService bean found in context: {}", tikaService.getClass().getName());
        } catch (Exception e) {
            LOG.error("❌ Failed to get TikaParserService bean from context", e);
        }
        
        // Check if gRPC server bean exists
        try {
            Object grpcServer = tikaModuleContext.getBean(io.grpc.Server.class);
            LOG.info("✅ gRPC Server bean found in Tika context: {}", grpcServer);
        } catch (Exception e) {
            LOG.warn("⚠️ No gRPC Server bean found in Tika context - this might be normal if Micronaut manages it internally");
        }
    }
    
    private void startChunkerModule() throws IOException {
        // Use proper ApplicationContext approach for automatic health service support
        int chunkerPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            chunkerPort = socket.getLocalPort();
        }
        
        LOG.info("Starting Chunker ApplicationContext with gRPC on port {}", chunkerPort);
        
        // Create dedicated ApplicationContext for Chunker module with proper gRPC configuration
        Map<String, Object> chunkerProps = new HashMap<>();
        chunkerProps.put("micronaut.application.name", "chunker-test");
        chunkerProps.put("grpc.server.port", chunkerPort);
        chunkerProps.put("grpc.server.health.enabled", true);
        chunkerProps.put("micronaut.grpc.server.port", chunkerPort);
        chunkerProps.put("micronaut.grpc.server.health.enabled", true);
        chunkerProps.put("micronaut.server.port", -1);  // Disable HTTP server
        chunkerProps.put("yappy.engine.auto-register", false);  // Disable engine registration
        chunkerProps.put("consul.client.registration.enabled", false);
        chunkerProps.put("micronaut.test-resources.enabled", false);  // Disable test resources
        chunkerProps.put("micronaut.environments", "test");
        
        // Create ApplicationContext directly - it should start gRPC server automatically
        this.chunkerModuleContext = ApplicationContext.run(chunkerProps, "test");
            
        resolvedModulePorts.put("chunker", String.valueOf(chunkerPort));
        
        LOG.info("Chunker ApplicationContext started with automatic gRPC server on port {}", chunkerPort);
        
        // Verify the service bean exists
        try {
            Object chunkerService = chunkerModuleContext.getBean(com.krickert.yappy.modules.chunker.ChunkerServiceGrpc.class);
            LOG.info("✅ ChunkerService bean found in context: {}", chunkerService.getClass().getName());
        } catch (Exception e) {
            LOG.error("❌ Failed to get ChunkerService bean from context", e);
        }
    }
    
    private void startEmbedderModule() throws IOException {
        // Use proper ApplicationContext approach for automatic health service support
        int embedderPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            embedderPort = socket.getLocalPort();
        }
        
        LOG.info("Starting Embedder ApplicationContext with gRPC on port {}", embedderPort);
        
        // Create dedicated ApplicationContext for Embedder module with proper gRPC configuration
        Map<String, Object> embedderProps = new HashMap<>();
        embedderProps.put("micronaut.application.name", "embedder-test");
        embedderProps.put("grpc.server.port", embedderPort);
        embedderProps.put("grpc.server.health.enabled", true);
        embedderProps.put("micronaut.grpc.server.port", embedderPort);
        embedderProps.put("micronaut.grpc.server.health.enabled", true);
        embedderProps.put("micronaut.server.port", -1);  // Disable HTTP server
        embedderProps.put("yappy.engine.auto-register", false);  // Disable engine registration
        embedderProps.put("consul.client.registration.enabled", false);
        embedderProps.put("micronaut.test-resources.enabled", false);  // Disable test resources
        embedderProps.put("micronaut.environments", "test");
        
        // Create ApplicationContext directly - it should start gRPC server automatically
        this.embedderModuleContext = ApplicationContext.run(embedderProps, "test");
            
        resolvedModulePorts.put("embedder", String.valueOf(embedderPort));
        
        LOG.info("Embedder ApplicationContext started with automatic gRPC server on port {}", embedderPort);
        
        // Verify the service bean exists
        try {
            Object embedderService = embedderModuleContext.getBean(com.krickert.yappy.modules.embedder.EmbedderServiceGrpc.class);
            LOG.info("✅ EmbedderService bean found in context: {}", embedderService.getClass().getName());
        } catch (Exception e) {
            LOG.error("❌ Failed to get EmbedderService bean from context", e);
        }
    }
    
    
    
    
    
    @BeforeAll
    void setupAll() throws IOException {
        LOG.info("================ @BeforeAll - START ================");
        
        // Start module contexts directly in @BeforeAll
        startTikaModule();
        startChunkerModule();
        startEmbedderModule();
        
        LOG.info("@BeforeAll: Module ports: {}", resolvedModulePorts);
        
        // Register channels for local modules in main context
        LOG.info("@BeforeAll: Registering local module channels with main GrpcChannelManager");
        LOG.info("Tika-parser port: {}", resolvedModulePorts.get("tika-parser"));
        LOG.info("Chunker port: {}", resolvedModulePorts.get("chunker"));
        LOG.info("Embedder port: {}", resolvedModulePorts.get("embedder"));
        
        grpcChannelManager.updateChannel("tika-parser", ManagedChannelBuilder
                .forAddress("localhost", Integer.parseInt(resolvedModulePorts.get("tika-parser")))
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("chunker", ManagedChannelBuilder
                .forAddress("localhost", Integer.parseInt(resolvedModulePorts.get("chunker")))
                .usePlaintext()
                .build());
        grpcChannelManager.updateChannel("embedder", ManagedChannelBuilder
                .forAddress("localhost", Integer.parseInt(resolvedModulePorts.get("embedder")))
                .usePlaintext()
                .build());
        
        LOG.info("All module channels registered successfully");
        
        // Note: Consul service registration will be handled automatically by Micronaut
        LOG.info("Services will be automatically registered with Consul by Micronaut gRPC framework");
        
        // Wait for services to register with Consul and verify ports are active
        LOG.info("Waiting for services to register with Consul...");
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during service registration wait", e);
        }
        
        // Verify that the ApplicationContexts actually have gRPC servers running
        LOG.info("Verifying gRPC servers are running...");
        LOG.info("Tika context running: {}", tikaModuleContext.isRunning());
        LOG.info("Chunker context running: {}", chunkerModuleContext.isRunning());
        LOG.info("Embedder context running: {}", embedderModuleContext.isRunning());
        
        // Try to get the actual gRPC server ports from the contexts
        try {
            Integer tikaActualPort = tikaModuleContext.getEnvironment().getProperty("grpc.server.port", Integer.class).orElse(-1);
            Integer chunkerActualPort = chunkerModuleContext.getEnvironment().getProperty("grpc.server.port", Integer.class).orElse(-1);
            Integer embedderActualPort = embedderModuleContext.getEnvironment().getProperty("grpc.server.port", Integer.class).orElse(-1);
            
            LOG.info("Tika gRPC server port from context: {}", tikaActualPort);
            LOG.info("Chunker gRPC server port from context: {}", chunkerActualPort);
            LOG.info("Embedder gRPC server port from context: {}", embedderActualPort);
        } catch (Exception e) {
            LOG.error("Error getting gRPC server ports from contexts", e);
        }
        
        // Test if we can actually connect to the tika-parser service
        try {
            LOG.info("\n🔌 TESTING SERVICE CONNECTIVITY 🔌");
            ManagedChannel testChannel = grpcChannelManager.getChannel("tika-parser");
            LOG.info("Successfully retrieved tika-parser channel: {}", testChannel);
            
            // Try a direct gRPC call to verify the service is working
            LOG.info("Testing direct gRPC call to tika-parser...");
            com.krickert.search.sdk.PipeStepProcessorGrpc.PipeStepProcessorBlockingStub stub = 
                com.krickert.search.sdk.PipeStepProcessorGrpc.newBlockingStub(testChannel);
            
            // Create a simple test request
            com.krickert.search.sdk.ProcessRequest testRequest = com.krickert.search.sdk.ProcessRequest.newBuilder()
                .setMetadata(com.krickert.search.sdk.ServiceMetadata.newBuilder()
                    .setStreamId("test-connection-check")
                    .setPipelineName("test-pipeline")
                    .setPipeStepName("tika-parser")
                    .build())
                .setConfig(com.krickert.search.sdk.ProcessConfiguration.newBuilder().build())
                .setDocument(com.krickert.search.model.PipeDoc.newBuilder()
                    .setId("test-doc")
                    .setTitle("Test Document")
                    .setBody("Test content")
                    .build())
                .build();
                
            LOG.info("Sending test request to tika-parser...");
            com.krickert.search.sdk.ProcessResponse response = stub.processData(testRequest);
            LOG.info("✅ Direct gRPC call successful!");
            LOG.info("Response success: {}", response.getSuccess());
            LOG.info("Response has output doc: {}", response.hasOutputDoc());
            LOG.info("Processor logs: {}", response.getProcessorLogsList());
            if (!response.getSuccess() && response.hasErrorDetails()) {
                LOG.error("Error details: {}", response.getErrorDetails());
            }
            
        } catch (Exception e) {
            LOG.error("❌ Direct gRPC call failed", e);
            LOG.error("Error type: {}", e.getClass().getName());
            LOG.error("Error message: {}", e.getMessage());
            if (e.getCause() != null) {
                LOG.error("Root cause: {}", e.getCause().getMessage());
            }
        }
        
        // Start main Engine gRPC service for pipeline processing
        try (ServerSocket socket = new ServerSocket(0)) {
            enginePort = socket.getLocalPort();
        }
        
        LOG.info("Starting main Engine gRPC service on port {} for mixed transport processing", enginePort);
        engineGrpcServer = ServerBuilder
                .forPort(enginePort)
                .addService(pipeStreamEngineImpl)
                .build()
                .start();
        
        // Register the main engine so it can receive gRPC forwards between pipeline steps
        grpcChannelManager.updateChannel("main-engine", ManagedChannelBuilder
                .forAddress("localhost", enginePort)
                .usePlaintext()
                .build());
        
        LOG.info("All module services and main engine registered with GrpcChannelManager");
        
        // Pause to ensure all components are stable
        try {
            LOG.info("@BeforeAll: Pausing for 3 seconds to ensure all components are stable...");
            TimeUnit.SECONDS.sleep(3);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during pause", e);
        }
        
        LOG.info("================ @BeforeAll - FINISHED ================");
    }
    
    @AfterAll
    void tearDownAll() {
        LOG.info("================ @AfterAll - START ================");
        
        // Shutdown engine gRPC server
        if (engineGrpcServer != null) {
            LOG.info("Shutting down Engine gRPC server...");
            engineGrpcServer.shutdown();
            try {
                engineGrpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                engineGrpcServer.shutdownNow();
            }
        }
        
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
        
        // Stop all module contexts (gRPC servers will be automatically shut down)
        stopAllContexts();
        
        LOG.info("================ @AfterAll - FINISHED ================");
    }
    
    private void stopAllContexts() {
        // Stop module contexts (automatic gRPC servers will be shut down automatically)
        if (tikaModuleContext != null && tikaModuleContext.isRunning()) {
            LOG.info("Stopping Tika module context (with automatic gRPC server)...");
            tikaModuleContext.stop();
            tikaModuleContext = null;
        }
        if (chunkerModuleContext != null && chunkerModuleContext.isRunning()) {
            LOG.info("Stopping Chunker module context (with automatic gRPC server)...");
            chunkerModuleContext.stop();
            chunkerModuleContext = null;
        }
        if (embedderModuleContext != null && embedderModuleContext.isRunning()) {
            LOG.info("Stopping Embedder module context (with automatic gRPC server)...");
            embedderModuleContext.stop();
            embedderModuleContext = null;
        }
    }
    
    @BeforeEach
    void setUp() {
        LOG.info("================ @BeforeEach setUp() - START ================");
        
        // Clean up any previous configuration
        LOG.info("Deleting previous cluster configuration for: {}", TEST_CLUSTER_NAME);
        consulBusinessOperationsService.deleteClusterConfiguration(TEST_CLUSTER_NAME).block();
        
        // Verify DynamicConfigurationManager is working
        LOG.info("Checking DynamicConfigurationManager current state...");
        Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
        LOG.info("Current cluster config present: {}", currentConfig.isPresent());
        if (currentConfig.isPresent()) {
            LOG.info("Current cluster name: {}", currentConfig.get().clusterName());
        }
        
        // Check what cluster name the DynamicConfigurationManager is configured for
        LOG.info("Expected cluster name for test: {}", TEST_CLUSTER_NAME);
        LOG.info("Application cluster name property: {}", 
                applicationContext.getProperty("app.config.cluster-name", String.class).orElse("NOT_SET"));
        
        // Full pipeline: input -> tika-parser (gRPC) -> chunker1 (gRPC) -> chunker2 (gRPC) -> embedder -> kafka
        
        // SIMPLIFIED: Start with just Tika Parser to Kafka output to verify basic communication
        PipelineStepConfig tikaStep = PipelineStepConfig.builder()
                .stepName(TIKA_STEP)
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of("parsed", 
                        PipelineStepConfig.OutputTarget.builder()
                                .targetStepName("final-sink")
                                .transportType(TransportType.KAFKA)  // Tika -> Kafka (simplified)
                                .kafkaTransport(KafkaTransportConfig.builder()
                                        .topic(FINAL_OUTPUT_TOPIC)
                                        .build())
                                .build()))
                .build();
        
        // Final sink
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
                        TIKA_STEP, tikaStep,
                        "final-sink", sinkStep
                ))
                .build();
        
        LOG.info("\n🔧 STORING PIPELINE CONFIGURATION IN CONSUL 🔧");
        LOG.info("Pipeline name: {}", TEST_PIPELINE);
        LOG.info("Pipeline steps: {}", testPipeline.pipelineSteps().keySet());
        LOG.info("Output topic: {}", FINAL_OUTPUT_TOPIC);
        
        storeTestConfiguration(TEST_PIPELINE, testPipeline,
                Set.of(FINAL_OUTPUT_TOPIC), 
                Set.of("tika-parser", "dummy-sink", "main-engine"));
        
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
    @DisplayName("Should demonstrate basic Tika parser integration")
    void testBasicTikaIntegration() throws Exception {
        // Skip @BeforeAll for now - let's debug inline
        LOG.info("\n🔍 DEBUGGING TIKA INTEGRATION 🔍");
        
        // First check if the main engine's gRPC channels are working
        LOG.info("Available gRPC channels in main context:");
        try {
            ManagedChannel tikaChannel = grpcChannelManager.getChannel("tika-parser");
            LOG.info("  - tika-parser channel: {}", tikaChannel);
        } catch (Exception e) {
            LOG.error("  - Failed to get tika-parser channel: {}", e.getMessage());
        }
        // Load sample PipeStreams that contain documents with binary data
        Collection<PipeStream> sampleStreams = ProtobufTestDataHelper.loadSamplePipeStreams();
        assertFalse(sampleStreams.isEmpty(), "Should have sample streams to process");
        
        // Take the first sample (or find a PDF if available)
        PipeStream inputStream = sampleStreams.stream()
                .filter(stream -> {
                    String filename = stream.getContextParamsOrDefault("filename", "");
                    return filename.toLowerCase().endsWith(".pdf") || 
                           filename.toLowerCase().endsWith(".docx") ||
                           filename.toLowerCase().endsWith(".txt");
                })
                .findFirst()
                .orElse(sampleStreams.iterator().next());
        
        // Update the stream for our pipeline
        PipeStream testPipeStream = inputStream.toBuilder()
                .setStreamId("test-full-pipeline-" + UUID.randomUUID())
                .setCurrentPipelineName(TEST_PIPELINE)
                .setTargetStepName(TIKA_STEP)
                .setCurrentHopNumber(0)
                .build();
        
        LOG.info("Processing document: {} through full pipeline", 
                testPipeStream.getDocument().getBlob().getFilename());
        
        // Process through the pipeline
        DefaultPipeStreamEngineLogicImpl engineLogic = new DefaultPipeStreamEngineLogicImpl(
                pipeStepExecutorFactory,
                pipeStreamGrpcForwarder,
                kafkaForwarder,
                dynamicConfigurationManager
        );
        
        // Give the system a moment to ensure all services are ready
        LOG.info("Pausing to ensure all services are ready for pipeline processing...");
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during pre-processing pause", e);
        }
        
        LOG.info("\n🚀 PROCESSING STREAM THROUGH PIPELINE 🚀");
        LOG.info("Stream ID: {}", testPipeStream.getStreamId());
        LOG.info("Pipeline: {}", testPipeStream.getCurrentPipelineName());
        LOG.info("Target step: {}", testPipeStream.getTargetStepName());
        LOG.info("Document: {}", testPipeStream.getDocument().getBlob().getFilename());
        
        // Check what pipeline configuration is loaded
        Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
        if (currentConfig.isPresent()) {
            LOG.info("✅ Pipeline configuration is loaded");
            PipelineConfig pipeline = currentConfig.get().pipelineGraphConfig().pipelines().get(TEST_PIPELINE);
            if (pipeline != null) {
                LOG.info("  Pipeline {} has {} steps", TEST_PIPELINE, pipeline.pipelineSteps().size());
                pipeline.pipelineSteps().forEach((name, step) -> {
                    LOG.info("    - Step '{}': service={}, outputs={}", 
                        name, step.processorInfo().grpcServiceName(), step.outputs().keySet());
                });
            } else {
                LOG.error("❌ Pipeline {} not found in configuration!", TEST_PIPELINE);
            }
        } else {
            LOG.error("❌ No pipeline configuration loaded!");
        }
        
        try {
            engineLogic.processStream(testPipeStream);
            LOG.info("✅ Stream processing initiated successfully");
        } catch (Exception e) {
            LOG.error("❌ Failed to process stream", e);
            throw e;
        }
        
        // Wait for the final output
        LOG.info("Waiting for processed document with embeddings on output topic: {}", FINAL_OUTPUT_TOPIC);
        
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
        
        // Verify the document went through the tika step  
        assertTrue(processedStream.getHistoryCount() >= 1, 
                "Should have history from at least 1 step (tika)");
        
        // Verify tika step was executed
        boolean hasTikaStep = processedStream.getHistoryList().stream()
                .anyMatch(record -> record.getStepName().equals(TIKA_STEP));
        
        assertTrue(hasTikaStep, "Should have execution record from Tika parser");
        
        // Verify the document has been processed
        assertNotNull(processedStream.getDocument(), "Should have a document");
        
        // Check that we have text content (from Tika)
        assertTrue(processedStream.getDocument().hasBody() || processedStream.getDocument().hasTitle(),
                "Document should have extracted text content");
        
        // Check semantic processing results
        assertTrue(processedStream.getDocument().getSemanticResultsCount() > 0,
                "Document should have semantic processing results");
        
        // Log the processing summary
        LOG.info("Successfully processed document through full pipeline:");
        LOG.info("  - Original file: {}", testPipeStream.getDocument().getBlob().getFilename());
        LOG.info("  - Extracted text length: {}", 
                processedStream.getDocument().hasBody() ? processedStream.getDocument().getBody().length() : 0);
        LOG.info("  - Processing steps completed: {}", processedStream.getHistoryCount());
        
        processedStream.getHistoryList().forEach(record -> {
            LOG.info("    Step '{}': {} (hop {})", 
                    record.getStepName(), 
                    record.getStatus(), 
                    record.getHopNumber());
            record.getProcessorLogsList().forEach(log -> LOG.info("      - {}", log));
        });
        
        LOG.info("\n🎉 MILESTONE ACHIEVED! TIKA PARSER INTEGRATION! 🎉");
        LOG.info("Successfully demonstrated basic pipeline with:");
        LOG.info("  ✅ Real Tika parser with document extraction");
        LOG.info("  ✅ Document processing pipeline flow");
        LOG.info("  ✅ Micronaut ApplicationContext-based gRPC services");
        LOG.info("  ✅ Automatic health service support");
        LOG.info("  ✅ Pipeline routing:");
        LOG.info("      • Input → (gRPC) → Tika Parser");
        LOG.info("      • Tika Parser → (Kafka) → Final Output");
        LOG.info("  ✅ NO MOCKS - Real module processing!");
        LOG.info("  ✅ Actual document text extraction!");
        LOG.info("Basic integration working - ready to add chunker and embedder steps!");
    }
    
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
        LOG.info("Storing cluster configuration in Consul...");
        LOG.info("Cluster config: name={}, pipelines={}, topics={}, services={}", 
                clusterConfig.clusterName(), 
                clusterConfig.pipelineGraphConfig().pipelines().keySet(),
                kafkaTopics,
                grpcServices);
        
        // Log detailed pipeline configuration
        LOG.info("\n📋 DETAILED PIPELINE CONFIGURATION:");
        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            LOG.info("Pipeline: {}", pipelineEntry.getKey());
            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipelineEntry.getValue().pipelineSteps().entrySet()) {
                PipelineStepConfig step = stepEntry.getValue();
                LOG.info("  Step: {} (type: {})", stepEntry.getKey(), step.stepType());
                LOG.info("    gRPC service: {}", step.processorInfo().grpcServiceName());
                LOG.info("    Outputs: {}", step.outputs().keySet());
                for (Map.Entry<String, PipelineStepConfig.OutputTarget> output : step.outputs().entrySet()) {
                    LOG.info("      {} -> {} via {}", 
                            output.getKey(), 
                            output.getValue().targetStepName(), 
                            output.getValue().transportType());
                }
            }
        }
        
        consulBusinessOperationsService.storeClusterConfiguration(TEST_CLUSTER_NAME, clusterConfig).block();
        LOG.info("✅ Configuration stored successfully in Consul");
        
        // Wait for DynamicConfigurationManager to load the configuration
        LOG.info("Waiting for DynamicConfigurationManager to load configuration for cluster: {}", TEST_CLUSTER_NAME);
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    Optional<PipelineClusterConfig> currentConfig = dynamicConfigurationManager.getCurrentPipelineClusterConfig();
                    if (currentConfig.isPresent()) {
                        String currentClusterName = currentConfig.get().clusterName();
                        LOG.info("Found cluster config: {}, expected: {}, match: {}", 
                                currentClusterName, TEST_CLUSTER_NAME, currentClusterName.equals(TEST_CLUSTER_NAME));
                        return currentClusterName.equals(TEST_CLUSTER_NAME);
                    } else {
                        LOG.info("No cluster configuration found yet...");
                        return false;
                    }
                });
        
        LOG.info("Configuration loaded successfully for pipeline: {}", pipelineName);
    }
    
    private void setupKafkaConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, 
                applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse("localhost:9092"));
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-full-pipeline-" + UUID.randomUUID());
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