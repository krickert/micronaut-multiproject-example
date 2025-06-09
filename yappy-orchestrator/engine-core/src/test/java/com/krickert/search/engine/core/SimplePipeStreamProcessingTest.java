package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
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
import com.krickert.search.config.pipeline.model.GrpcTransportConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simplified integration test for PipeStream processing through the engine.
 * Tests the complete flow of a document through a multi-step pipeline.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class SimplePipeStreamProcessingTest {
    
    private static final Logger logger = LoggerFactory.getLogger(SimplePipeStreamProcessingTest.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    @Inject
    ApplicationContext applicationContext;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    private ConsulClient consulClient;
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
        // Get Consul configuration
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        // Create test cluster
        testClusterName = TestClusterHelper.createTestCluster("pipestream-test");
        
        // Start mock services
        startMockServices();
        
        // Register mock services in Consul
        registerMockServices();
        
        // Setup pipeline configuration in Consul
        setupPipelineConfiguration();
        
        // Create pipeline engine
        pipelineEngine = new PipelineEngineImpl(
            consulClient, 
            configManager,
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
        
        // Cleanup pipeline engine - this will save buffered test data!
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
        
        // Cleanup test cluster
        if (consulClient != null && testClusterName != null) {
            TestClusterHelper.cleanupTestCluster(consulClient, testClusterName);
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
            .setCurrentHopNumber(0)
            .putContextParams("test-run", "true")
            .putContextParams("source", "unit-test")
            .build();
        
        // Process through pipeline
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify();
        
        // Verify all steps were executed
        assertThat(processingCounts.get("tika-parser").get()).isEqualTo(1);
        assertThat(processingCounts.get("chunker").get()).isEqualTo(1);
        assertThat(processingCounts.get("embedder").get()).isEqualTo(1);
        
        // Verify Tika configuration was applied
        ProcessRequest tikaRequest = capturedRequests.get("tika-parser");
        assertThat(tikaRequest).isNotNull();
        assertThat(tikaRequest.getConfig().hasCustomJsonConfig()).isTrue();
        
        // Verify the custom config contains expected fields
        Struct tikaConfig = tikaRequest.getConfig().getCustomJsonConfig();
        assertThat(tikaConfig.getFieldsMap()).containsKey("parsingOptions");
        
        // Verify chunker configuration
        ProcessRequest chunkerRequest = capturedRequests.get("chunker");
        assertThat(chunkerRequest).isNotNull();
        Struct chunkerConfig = chunkerRequest.getConfig().getCustomJsonConfig();
        assertThat(chunkerConfig.getFieldsMap()).containsKey("chunk_size");
        assertThat(chunkerConfig.getFieldsMap()).containsKey("chunk_overlap");
        
        // Verify document transformation through pipeline
        ProcessResponse embedderResponse = capturedResponses.get("embedder");
        assertThat(embedderResponse).isNotNull();
        assertThat(embedderResponse.hasOutputDoc()).isTrue();
        
        PipeDoc finalDoc = embedderResponse.getOutputDoc();
        // Should have semantic processing results from chunker
        assertThat(finalDoc.getSemanticResultsCount()).isGreaterThan(0);
        // Should have embeddings
        assertThat(finalDoc.getNamedEmbeddingsCount()).isGreaterThan(0);
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
        
        // Verify chunker and embedder were executed
        assertThat(processingCounts.get("chunker").get()).isEqualTo(1);
        assertThat(processingCounts.get("embedder").get()).isEqualTo(1);
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
            .setCurrentHopNumber(0)
            .build();
        
        // Process - should complete even with error
        StepVerifier.create(pipelineEngine.processMessage(pipeStream))
            .expectComplete()
            .verify();
        
        // Verify processing stopped at error
        assertThat(processingCounts.get("tika-parser").get()).isEqualTo(1);
        assertThat(processingCounts.get("chunker")).isNull(); // Should not reach chunker
        assertThat(processingCounts.get("embedder")).isNull(); // Should not reach embedder
    }
    
    private void setupPipelineConfiguration() {
        try {
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
            
            // Store in Consul
            String key = String.format("configs/%s/pipelines/%s", testClusterName, pipeline.name());
            consulClient.setKVValue(key, objectMapper.writeValueAsString(pipeline));
            
            // Initialize config manager
            configManager.initialize(testClusterName);
            
            logger.info("Pipeline configuration setup complete");
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup pipeline configuration", e);
        }
    }
    
    private PipelineStepConfig createTikaStep() {
        ObjectNode tikaConfig = objectMapper.createObjectNode();
        ObjectNode parsingOptions = tikaConfig.putObject("parsingOptions");
        parsingOptions.put("maxContentLength", -1);
        parsingOptions.put("extractMetadata", true);
        
        return new PipelineStepConfig(
            "tika-parser",
            StepType.PIPELINE,
            "Extract text and metadata from documents",
            null,
            new PipelineStepConfig.JsonConfigOptions(tikaConfig, Map.of()),
            null,
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "chunker", 
                TransportType.GRPC,
                new GrpcTransportConfig(null, Map.of()),
                null)),
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("tika-parser", null)
        );
    }
    
    private PipelineStepConfig createChunkerStep() {
        ObjectNode chunkerConfig = objectMapper.createObjectNode();
        chunkerConfig.put("source_field", "body");
        chunkerConfig.put("chunk_size", 500);
        chunkerConfig.put("chunk_overlap", 50);
        chunkerConfig.put("chunk_config_id", "test_config");
        
        return new PipelineStepConfig(
            "chunker",
            StepType.PIPELINE,
            "Split text into semantic chunks",
            null,
            new PipelineStepConfig.JsonConfigOptions(chunkerConfig, Map.of()),
            null,
            Map.of("default", new PipelineStepConfig.OutputTarget(
                "embedder", 
                TransportType.GRPC,
                new GrpcTransportConfig(null, Map.of()),
                null)),
            null, null, null, null, null,
            new PipelineStepConfig.ProcessorInfo("chunker", null)
        );
    }
    
    private PipelineStepConfig createEmbedderStep() {
        ObjectNode embedderConfig = objectMapper.createObjectNode();
        embedderConfig.put("model", "test-embedding-model");
        embedderConfig.put("dimension", 384);
        
        return new PipelineStepConfig(
            "embedder",
            StepType.SINK,
            "Generate embeddings for chunks",
            null,
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
    }
    
    private void registerMockServices() {
        TestClusterHelper.registerServiceInCluster(
            consulClient, testClusterName, "tika-parser", "tika-1",
            "localhost", mockTikaServer.getPort(),
            Map.of("module-type", "document-parser")
        );
        
        TestClusterHelper.registerServiceInCluster(
            consulClient, testClusterName, "chunker", "chunker-1",
            "localhost", mockChunkerServer.getPort(),
            Map.of("module-type", "text-processor")
        );
        
        TestClusterHelper.registerServiceInCluster(
            consulClient, testClusterName, "embedder", "embedder-1",
            "localhost", mockEmbedderServer.getPort(),
            Map.of("module-type", "embedding-generator")
        );
        
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        
        private PipeDoc processDocument(ProcessRequest request) throws Exception {
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