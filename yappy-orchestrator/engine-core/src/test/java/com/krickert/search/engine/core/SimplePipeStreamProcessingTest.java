package com.krickert.search.engine.core;

import com.krickert.search.config.consul.service.BusinessOperationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Empty;
import com.google.protobuf.Struct;
import com.google.protobuf.Timestamp;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import com.krickert.search.engine.core.routing.Router;
import com.krickert.search.engine.core.routing.DefaultRouter;
import com.krickert.search.engine.core.routing.ConfigurationBasedRoutingStrategy;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.engine.core.transport.grpc.GrpcMessageForwarder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Simplified integration test for PipeStream processing through the engine.
 * Tests the complete flow of a document through a multi-step pipeline.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SimplePipeStreamProcessingTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SimplePipeStreamProcessingTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
        .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    BusinessOperationsService businessOpsService;
    
    @Inject
    TestClusterHelper testClusterHelper;
    
    private Router router;
    private GrpcMessageForwarder grpcForwarder;
    
    private String testClusterName;
    private PipelineEngineImpl pipelineEngine;
    private Server mockTikaServer;
    private Server mockChunkerServer;
    private Server mockEmbedderServer;
    
    // Track processing for assertions
    private final Map<String, AtomicInteger> processingCounts = new ConcurrentHashMap<>();
    private final Map<String, ProcessRequest> capturedRequests = new ConcurrentHashMap<>();
    private final Map<String, ProcessResponse> capturedResponses = new ConcurrentHashMap<>();
    
    @BeforeAll
    void setup() throws IOException {
        MockitoAnnotations.openMocks(this);
        
        // Create test cluster
        testClusterName = testClusterHelper.createTestCluster("pipestream-test");
        
        // Start mock services
        startMockServices();
        
        // Register mock services in Consul
        registerMockServices();
        
        // Setup pipeline configuration in Consul
        setupPipelineConfiguration();
        
        // Create router with GrpcMessageForwarder in test mode
        grpcForwarder = new GrpcMessageForwarder(businessOpsService, testClusterName, true);
        ConfigurationBasedRoutingStrategy routingStrategy = new ConfigurationBasedRoutingStrategy(businessOpsService, testClusterName);
        router = new DefaultRouter(List.of(grpcForwarder), routingStrategy);
        
        // Create pipeline engine
        pipelineEngine = new PipelineEngineImpl(
            businessOpsService,
            router,
            testClusterName,
            true,  // Enable buffer
            100,   // Capacity
            3,     // Precision
            1.0    // Sample everything
        );
    }
    
    @AfterAll
    void cleanup() throws InterruptedException {
        // Shutdown servers
        if (mockTikaServer != null) {
            mockTikaServer.shutdown();
            mockTikaServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (mockChunkerServer != null) {
            mockChunkerServer.shutdown();
            mockChunkerServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (mockEmbedderServer != null) {
            mockEmbedderServer.shutdown();
            mockEmbedderServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // Cleanup gRPC forwarder
        if (grpcForwarder != null) {
            grpcForwarder.shutdown();
        }
        
        // Cleanup pipeline engine - this will save buffered test data!
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
        
        // Cleanup test cluster
        if (testClusterName != null) {
            testClusterHelper.cleanupTestCluster(testClusterName).block();
        }
    }
    
    @BeforeEach
    void resetCounters() {
        processingCounts.clear();
        capturedRequests.clear();
        capturedResponses.clear();
    }
    
    @Test
    void testCompleteDocumentProcessingPipeline() {
        // Ensure configuration is available before test
        var configOpt = configManager.getCurrentPipelineClusterConfig();
        assertThat(configOpt).isPresent();
        logger.info("Configuration available: {}", configOpt.get().clusterName());
        
        // Create a test document
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-001")
            .setTitle("Test Document for Pipeline Processing")
            .setBody("This is a comprehensive test document with enough content to demonstrate " +
                    "the complete pipeline processing flow. It contains multiple sentences that " +
                    "will be processed by Tika parser, then chunked into smaller pieces, and " +
                    "finally have embeddings generated. Each step will transform the document.")
            .setSourceMimeType("text/plain")
            .setSourceUri("test://documents/test-doc-001.txt")
            .setCreationDate(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000).build())
            .build();
        
        // Create PipeStream
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("document-processing-pipeline")
            .setTargetStepName("tika-parser")  // Start at first step
            .setCurrentHopNumber(0)
            .putContextParams("test-run", "true")
            .putContextParams("source", "unit-test")
            .build();
        
        // Process through pipeline
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify();
        
        // Verify at least the first step was executed
        assertThat(processingCounts.get("tika-parser")).isNotNull();
        assertThat(processingCounts.get("tika-parser").get()).isEqualTo(1);
        
        // Note: In this test setup, the mock services don't actually forward messages
        // through the pipeline. They just return success. In a real integration test,
        // the services would need to call the engine back to process the next step.
        
        // Verify Tika configuration was applied
        ProcessRequest tikaRequest = capturedRequests.get("tika-parser");
        assertThat(tikaRequest).isNotNull();
        assertThat(tikaRequest.getConfig().hasCustomJsonConfig()).isTrue();
        
        // Verify the custom config contains expected fields
        Struct tikaConfig = tikaRequest.getConfig().getCustomJsonConfig();
        assertThat(tikaConfig.getFieldsMap()).containsKey("parsingOptions");
        
        // Verify Tika response was captured
        ProcessResponse tikaResponse = capturedResponses.get("tika-parser");
        assertThat(tikaResponse).isNotNull();
        assertThat(tikaResponse.getSuccess()).isTrue();
    }
    
    @Test
    void testPipelineWithTargetStepOverride() {
        // Create a PipeStream that skips tika and goes directly to chunker
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-002")
            .setBody("Already extracted text that should go directly to chunker")
            .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-direct-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("document-processing-pipeline")
            .setTargetStepName("chunker")  // Skip tika-parser
            .setCurrentHopNumber(0)
            .build();
        
        // Process
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify();
        
        // Verify tika was skipped
        assertThat(processingCounts.get("tika-parser")).isNull();
        
        // Verify chunker was executed
        assertThat(processingCounts.get("chunker")).isNotNull();
        assertThat(processingCounts.get("chunker").get()).isEqualTo(1);
    }
    
    @Test
    void testPipelineErrorHandling() {
        // Create a document that will trigger an error
        PipeDoc document = PipeDoc.newBuilder()
            .setId("test-doc-error")
            .setBody("TRIGGER_ERROR")  // Special content that causes failure
            .build();
        
        PipeStream pipeStream = PipeStream.newBuilder()
            .setStreamId("stream-error-" + System.currentTimeMillis())
            .setDocument(document)
            .setCurrentPipelineName("document-processing-pipeline")
            .setTargetStepName("tika-parser")  // Add explicit target
            .setCurrentHopNumber(0)
            .build();
        
        // Process - should error when service returns failure
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectError(RuntimeException.class)
            .verify();
        
        // Verify processing happened
        assertThat(processingCounts.get("tika-parser")).isNotNull();
        assertThat(processingCounts.get("tika-parser").get()).isEqualTo(1);
        
        // Verify error response was captured
        ProcessResponse errorResponse = capturedResponses.get("tika-parser");
        assertThat(errorResponse).isNotNull();
        assertThat(errorResponse.getSuccess()).isFalse();
    }
    
    private void setupPipelineConfiguration() {
        try {
            // First, register schemas in Consul
            registerSchemasInConsul();
            
            // Create pipeline steps
            var tikaStep = createTikaStep();
            var chunkerStep = createChunkerStep();
            var embedderStep = createEmbedderStep();
            
            // Create pipeline with steps map
            var pipelineSteps = Map.of(
                "tika-parser", tikaStep,
                "chunker", chunkerStep,
                "embedder", embedderStep
            );
            
            var pipeline = new PipelineConfig(
                "document-processing-pipeline",
                pipelineSteps
            );
            
            // Create pipeline graph config containing the pipeline
            var pipelineGraphConfig = new PipelineGraphConfig(
                Map.of("document-processing-pipeline", pipeline)
            );
            
            // Create module configurations for the pipeline with schema references
            var tikaModule = new PipelineModuleConfiguration(
                "tika-parser",
                "tika-parser",
                new com.krickert.search.config.pipeline.model.SchemaReference("tika-parser-schema", 1),
                null   // No custom config at module level
            );
            
            var chunkerModule = new PipelineModuleConfiguration(
                "chunker",
                "chunker",
                new com.krickert.search.config.pipeline.model.SchemaReference("chunker-schema", 1),
                null
            );
            
            var embedderModule = new PipelineModuleConfiguration(
                "embedder",
                "embedder",
                new com.krickert.search.config.pipeline.model.SchemaReference("embedder-schema", 1),
                null
            );
            
            // Create module map
            var moduleMap = new PipelineModuleMap(Map.of(
                "tika-parser", tikaModule,
                "chunker", chunkerModule,
                "embedder", embedderModule
            ));
            
            // Create cluster config
            var clusterConfig = new PipelineClusterConfig(
                testClusterName,
                pipelineGraphConfig,
                moduleMap,
                "document-processing-pipeline",
                Set.of(),  // No Kafka topics for this test
                Set.of("tika-parser", "chunker", "embedder")  // gRPC services
            );
            
            // Store in Consul
            String key = String.format("configs/%s", testClusterName);
            String configJson = objectMapper.writeValueAsString(clusterConfig);
            logger.info("Storing pipeline configuration at key: {} with content: {}", key, configJson);
            businessOpsService.putValue(key, configJson).block();
            
            // Trust that the value was stored successfully since putValue didn't throw
            logger.info("Configuration stored in Consul at key: {}", key);
            
            // Initialize config manager
            logger.info("Initializing config manager for cluster: {}", testClusterName);
            configManager.initialize(testClusterName);
            
            // Wait for the configuration to be picked up by the watch
            await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    // Check if the pipeline configuration is available
                    var loadedConfigOpt = configManager.getCurrentPipelineClusterConfig();
                    logger.debug("Config manager returned: {}", loadedConfigOpt);
                    assertThat(loadedConfigOpt).isPresent();
                    
                    var loadedConfig = loadedConfigOpt.get();
                    assertThat(loadedConfig.clusterName()).isEqualTo(testClusterName);
                    assertThat(loadedConfig.pipelineGraphConfig()).isNotNull();
                    assertThat(loadedConfig.pipelineGraphConfig().pipelines())
                        .containsKey("document-processing-pipeline");
                });
            
            logger.info("Pipeline configuration setup complete and verified");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup pipeline configuration", e);
        }
    }
    
    private void registerSchemasInConsul() throws Exception {
        // Create simple test schemas for each module
        ObjectNode tikaSchema = objectMapper.createObjectNode();
        tikaSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
        tikaSchema.put("type", "object");
        ObjectNode tikaProps = objectMapper.createObjectNode();
        ObjectNode parsingOptionsSchema = objectMapper.createObjectNode();
        parsingOptionsSchema.put("type", "object");
        ObjectNode parsingOptionsProps = objectMapper.createObjectNode();
        parsingOptionsProps.set("maxContentLength", objectMapper.createObjectNode().put("type", "integer"));
        parsingOptionsProps.set("extractMetadata", objectMapper.createObjectNode().put("type", "boolean"));
        parsingOptionsSchema.set("properties", parsingOptionsProps);
        tikaProps.set("parsingOptions", parsingOptionsSchema);
        tikaSchema.set("properties", tikaProps);
        
        ObjectNode chunkerSchema = objectMapper.createObjectNode();
        chunkerSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
        chunkerSchema.put("type", "object");
        ObjectNode chunkerProps = objectMapper.createObjectNode();
        chunkerProps.set("chunk_size", objectMapper.createObjectNode().put("type", "integer"));
        chunkerProps.set("chunk_overlap", objectMapper.createObjectNode().put("type", "integer"));
        chunkerProps.set("source_field", objectMapper.createObjectNode().put("type", "string"));
        chunkerProps.set("chunk_config_id", objectMapper.createObjectNode().put("type", "string"));
        chunkerSchema.set("properties", chunkerProps);
        
        ObjectNode embedderSchema = objectMapper.createObjectNode();
        embedderSchema.put("$schema", "http://json-schema.org/draft-07/schema#");
        embedderSchema.put("type", "object");
        ObjectNode embedderProps = objectMapper.createObjectNode();
        embedderProps.set("model", objectMapper.createObjectNode().put("type", "string"));
        embedderProps.set("dimension", objectMapper.createObjectNode().put("type", "integer"));
        embedderSchema.set("properties", embedderProps);
        
        // Store schemas using business operations service
        
        // Create SchemaVersionData objects that the system expects
        var tikaSchemaVersion = new com.krickert.search.config.schema.model.SchemaVersionData(
            1L, // globalId
            "tika-parser-schema", // subject
            1, // version
            tikaSchema.toString(), // schemaContent
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Test schema for tika-parser"
        );
        
        var chunkerSchemaVersion = new com.krickert.search.config.schema.model.SchemaVersionData(
            2L, // globalId
            "chunker-schema", // subject
            1, // version
            chunkerSchema.toString(), // schemaContent
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Test schema for chunker"
        );
        
        var embedderSchemaVersion = new com.krickert.search.config.schema.model.SchemaVersionData(
            3L, // globalId
            "embedder-schema", // subject
            1, // version
            embedderSchema.toString(), // schemaContent
            com.krickert.search.config.schema.model.SchemaType.JSON_SCHEMA,
            com.krickert.search.config.schema.model.SchemaCompatibility.NONE,
            java.time.Instant.now(),
            "Test schema for embedder"
        );
        
        // Store as JSON
        businessOpsService.storeSchemaVersion("tika-parser-schema", 1, tikaSchemaVersion).block();
        businessOpsService.storeSchemaVersion("chunker-schema", 1, chunkerSchemaVersion).block();
        businessOpsService.storeSchemaVersion("embedder-schema", 1, embedderSchemaVersion).block();
        
        logger.info("Registered test schemas in Consul");
    }
    
    private PipelineStepConfig createTikaStep() {
        // Create valid tika configuration
        ObjectNode tikaConfig = objectMapper.createObjectNode();
        ObjectNode parsingOptions = objectMapper.createObjectNode();
        parsingOptions.put("maxContentLength", -1);
        parsingOptions.put("extractMetadata", true);
        tikaConfig.set("parsingOptions", parsingOptions);
        
        return new PipelineStepConfig(
            "tika-parser",
            StepType.PIPELINE,
            "Extract text and metadata from documents",
            null,  // Schema ID not needed at step level when module has schema reference
            new PipelineStepConfig.JsonConfigOptions(tikaConfig, Map.of()),
            null,
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "chunker", 
                TransportType.GRPC,
                new GrpcTransportConfig("chunker", Map.of()),
                null)),
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("tika-parser", null)
        );
    }
    
    private PipelineStepConfig createChunkerStep() {
        // Create valid chunker configuration
        ObjectNode chunkerConfig = objectMapper.createObjectNode();
        chunkerConfig.put("source_field", "body");
        chunkerConfig.put("chunk_size", 500);
        chunkerConfig.put("chunk_overlap", 50);
        chunkerConfig.put("chunk_config_id", "test_config");
        
        return new PipelineStepConfig(
            "chunker",
            StepType.PIPELINE,
            "Split text into semantic chunks",
            null,  // Schema ID not needed at step level when module has schema reference
            new PipelineStepConfig.JsonConfigOptions(chunkerConfig, Map.of()),
            null,
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "embedder", 
                TransportType.GRPC,
                new GrpcTransportConfig("embedder", Map.of()),
                null)),
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("chunker", null)
        );
    }
    
    private PipelineStepConfig createEmbedderStep() {
        // Create valid embedder configuration
        ObjectNode embedderConfig = objectMapper.createObjectNode();
        embedderConfig.put("model", "test-embedding-model");
        embedderConfig.put("dimension", 384);
        
        return new PipelineStepConfig(
            "embedder",
            StepType.SINK,
            "Generate embeddings for chunks",
            null,  // Schema ID not needed at step level when module has schema reference
            new PipelineStepConfig.JsonConfigOptions(embedderConfig, Map.of()),
            null,
            Map.of(),  // Final step, no outputs
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("embedder", null)
        );
    }
    
    private void startMockServices() throws IOException {
        // Start mock tika parser
        mockTikaServer = ServerBuilder.forPort(0)
            .addService(new TestPipeStepProcessor("tika-parser"))
            .build()
            .start();
        
        // Start mock chunker
        mockChunkerServer = ServerBuilder.forPort(0)
            .addService(new TestPipeStepProcessor("chunker"))
            .build()
            .start();
        
        // Start mock embedder
        mockEmbedderServer = ServerBuilder.forPort(0)
            .addService(new TestPipeStepProcessor("embedder"))
            .build()
            .start();
        
        logger.info("Started mock services - tika:{}, chunker:{}, embedder:{}", 
            mockTikaServer.getPort(), mockChunkerServer.getPort(), mockEmbedderServer.getPort());
        
        // Give servers a moment to fully start up
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for servers to start", e);
        }
    }
    
    private void registerMockServices() {
        testClusterHelper.registerServiceInCluster(
            testClusterName, "tika-parser", "tika-1",
            "localhost", mockTikaServer.getPort(),
            Map.of("module-type", "document-parser")
        ).block();
        
        testClusterHelper.registerServiceInCluster(
            testClusterName, "chunker", "chunker-1",
            "localhost", mockChunkerServer.getPort(),
            Map.of("module-type", "text-processor")
        ).block();
        
        testClusterHelper.registerServiceInCluster(
            testClusterName, "embedder", "embedder-1",
            "localhost", mockEmbedderServer.getPort(),
            Map.of("module-type", "embedding-generator")
        ).block();
        
        // Wait for services to be registered (in test mode, we don't need healthy services)
        await().atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                // Check if services are registered
                var tikaServices = businessOpsService.getServiceInstances(
                    testClusterName + "-tika-parser").block();
                var chunkerServices = businessOpsService.getServiceInstances(
                    testClusterName + "-chunker").block();
                var embedderServices = businessOpsService.getServiceInstances(
                    testClusterName + "-embedder").block();
                
                logger.info("Registered services - tika: {}, chunker: {}, embedder: {}", 
                    tikaServices != null ? tikaServices.size() : 0,
                    chunkerServices != null ? chunkerServices.size() : 0,
                    embedderServices != null ? embedderServices.size() : 0);
                
                // Verify services are registered
                assertThat(tikaServices).isNotNull().isNotEmpty();
                assertThat(chunkerServices).isNotNull().isNotEmpty();
                assertThat(embedderServices).isNotNull().isNotEmpty();
            });
        
        logger.info("All services registered successfully");
    }
    
    /**
     * Test implementation of PipeStepProcessor that simulates real processing.
     */
    class TestPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        
        private final String serviceName;
        
        TestPipeStepProcessor(String serviceName) {
            this.serviceName = serviceName;
        }
        
        @Override
        public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
            try {
                // Track processing
                processingCounts.computeIfAbsent(serviceName, k -> new AtomicInteger()).incrementAndGet();
                capturedRequests.put(serviceName, request);
                
                logger.info("{} processing document: {} with config: {}", 
                    serviceName, request.getDocument().getId(),
                    JsonFormat.printer().print(request.getConfig()));
                
                // Check for error trigger
                if (request.getDocument().getBody().equals("TRIGGER_ERROR")) {
                    ProcessResponse errorResponse = ProcessResponse.newBuilder()
                        .setSuccess(false)
                        .setErrorDetails(Struct.newBuilder()
                            .putFields("error", com.google.protobuf.Value.newBuilder()
                                .setStringValue("Simulated error").build())
                            .build())
                        .addProcessorLogs("Error triggered by test")
                        .build();
                        
                    capturedResponses.put(serviceName, errorResponse);
                    responseObserver.onNext(errorResponse);
                    responseObserver.onCompleted();
                    return;
                }
                
                // Process based on service type
                PipeDoc outputDoc = processDocument(request);
                
                ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .setOutputDoc(outputDoc)
                    .addProcessorLogs("Processed by " + serviceName)
                    .build();
                
                capturedResponses.put(serviceName, response);
                responseObserver.onNext(response);
                responseObserver.onCompleted();
                
            } catch (Exception e) {
                logger.error("Error in {} processor", serviceName, e);
                responseObserver.onError(e);
            }
        }
        
        private PipeDoc processDocument(ProcessRequest request) {
            PipeDoc inputDoc = request.getDocument();
            
            switch (serviceName) {
                case "tika-parser":
                    // Simulate metadata extraction
                    return inputDoc.toBuilder()
                        .setProcessedDate(Timestamp.newBuilder()
                            .setSeconds(System.currentTimeMillis() / 1000)
                            .build())
                        .setCustomData(Struct.newBuilder()
                            .putFields("extracted_by", com.google.protobuf.Value.newBuilder()
                                .setStringValue("tika-parser").build()))
                        .build();
                        
                case "chunker":
                    // Simulate chunking
                    SemanticChunk chunk1 = SemanticChunk.newBuilder()
                        .setChunkId("chunk-1")
                        .setChunkNumber(1)
                        .setEmbeddingInfo(ChunkEmbedding.newBuilder()
                            .setTextContent(inputDoc.getBody().substring(0, Math.min(100, inputDoc.getBody().length())))
                            .setChunkId("chunk-1")
                            .build())
                        .build();
                        
                    SemanticChunk chunk2 = SemanticChunk.newBuilder()
                        .setChunkId("chunk-2")
                        .setChunkNumber(2)
                        .setEmbeddingInfo(ChunkEmbedding.newBuilder()
                            .setTextContent(inputDoc.getBody().substring(Math.min(50, inputDoc.getBody().length())))
                            .setChunkId("chunk-2")
                            .build())
                        .build();
                        
                    SemanticProcessingResult semanticResult = SemanticProcessingResult.newBuilder()
                        .setResultId("chunking-result-1")
                        .setSourceFieldName("body")
                        .setChunkConfigId("test_config")
                        .addChunks(chunk1)
                        .addChunks(chunk2)
                        .build();
                        
                    return inputDoc.toBuilder()
                        .addSemanticResults(semanticResult)
                        .build();
                        
                case "embedder":
                    // Simulate embedding generation
                    Embedding docEmbedding = Embedding.newBuilder()
                        .setModelId("test-embedding-model")
                        .addAllVector(List.of(0.1f, 0.2f, 0.3f, 0.4f, 0.5f))
                        .build();
                        
                    return inputDoc.toBuilder()
                        .putNamedEmbeddings("document_embedding", docEmbedding)
                        .build();
                        
                default:
                    return inputDoc;
            }
        }
        
        @Override
        public void getServiceRegistration(Empty request, 
                StreamObserver<ServiceRegistrationData> responseObserver) {
            var registrationData = ServiceRegistrationData.newBuilder()
                .setModuleName(serviceName)
                .build();
            
            responseObserver.onNext(registrationData);
            responseObserver.onCompleted();
        }
    }
}