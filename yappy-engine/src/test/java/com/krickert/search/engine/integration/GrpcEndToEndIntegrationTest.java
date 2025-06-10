package com.krickert.search.engine.integration;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.engine.pipeline.PipelineExecutionService;
import com.krickert.search.engine.registration.ModuleRegistrationService;
import com.krickert.search.engine.test.modules.TestSinkModule;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.yappy.registration.api.HealthCheckType;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for gRPC-based pipeline processing.
 * Tests: Registration → Health Check → Pipeline Execution → Message Processing
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@io.micronaut.context.annotation.Property(name = "app.kafka.slot-management.enabled", value = "false")
@io.micronaut.context.annotation.Property(name = "app.kafka.monitoring.enabled", value = "false")
@io.micronaut.context.annotation.Property(name = "kafka.enabled", value = "false")
public class GrpcEndToEndIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(GrpcEndToEndIntegrationTest.class);
    
    @Inject
    private ModuleRegistrationService registrationService;
    
    @Inject
    private PipelineExecutionService pipelineExecutionService;
    
    @Inject
    private ConsulBusinessOperationsService consulService;
    
    // Test infrastructure
    private Server grpcServer;
    private TestSinkModule testSink;
    private int grpcPort;
    private static final String TEST_PIPELINE_ID = "test-pipeline";
    private static final String TEST_SINK_MODULE_ID = "test-sink-module";
    private static final String CLUSTER_NAME = "test-cluster";
    
    @BeforeAll
    void setupGrpcServer() throws IOException {
        // Find a free port
        try (ServerSocket socket = new ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        
        // Create and start the test sink gRPC server
        testSink = new TestSinkModule();
        grpcServer = ServerBuilder.forPort(grpcPort)
            .addService(testSink)
            .build()
            .start();
        
        LOG.info("Started test sink gRPC server on port: {}", grpcPort);
    }
    
    @AfterAll
    void teardownGrpcServer() {
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
            }
        }
    }
    
    @BeforeEach
    void setup() {
        testSink.reset();
    }
    
    @Test
    @DisplayName("Should process message through single-step pipeline")
    void testSingleStepPipelineProcessing() throws Exception {
        // Step 1: Register the test sink module
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
            .setImplementationId(TEST_SINK_MODULE_ID)
            .setInstanceServiceName("Test Sink Service")
            .setModuleSoftwareVersion("1.0.0")
            .setHealthCheckType(HealthCheckType.GRPC)
            .setHealthCheckEndpoint("/health")
            .setHost("localhost")
            .setPort(grpcPort)
            .build();
        
        StepVerifier.create(registrationService.registerModule(request))
            .assertNext(status -> {
                assertTrue(status.getSuccess());
                assertNotNull(status.getRegisteredServiceId());
            })
            .verifyComplete();
        
        // Get the actual registered service ID
        String registeredServiceId = request.getImplementationId();
        
        // Verify registration
        StepVerifier.create(registrationService.isModuleHealthy(registeredServiceId))
            .expectNext(true)
            .verifyComplete();
        
        // Step 2: Create pipeline configuration
        PipelineConfig pipelineConfig = createSingleStepPipeline();
        
        // Register pipeline with execution service (skip Consul for this test)
        StepVerifier.create(pipelineExecutionService.createOrUpdatePipeline(TEST_PIPELINE_ID, pipelineConfig))
            .expectNext(true)
            .verifyComplete();
        
        // Step 3: Process a document through the pipeline
        PipeDoc testDoc = PipeDoc.newBuilder()
            .setId("test-doc-1")
            .setTitle("Test Document")
            .setBody("This is a test document for end-to-end testing")
            .build();
        
        // Set expectation in test sink
        testSink.setExpectedMessageCount(1);
        
        // Process the document
        StepVerifier.create(pipelineExecutionService.processDocument(TEST_PIPELINE_ID, testDoc))
            .assertNext(processedDoc -> {
                assertNotNull(processedDoc);
                assertEquals("test-doc-1", processedDoc.getId());
                // Check if the sink added its marker
                assertTrue(processedDoc.getKeywordsList().contains("processed-by-test-sink"));
            })
            .verifyComplete();
        
        // Wait for the sink to receive the message
        assertTrue(testSink.awaitMessages(5, TimeUnit.SECONDS), "Test sink should receive the message");
        
        // Verify the sink received the correct data
        assertEquals(1, testSink.getProcessedCount());
        
        var receivedStreams = testSink.getReceivedStreams();
        assertEquals(1, receivedStreams.size());
        
        PipeStream receivedStream = receivedStreams.get(0);
        assertEquals("test-doc-1", receivedStream.getDocument().getId());
        assertEquals(TEST_PIPELINE_ID, receivedStream.getCurrentPipelineName());
        
        // Check validation results
        var validationResults = testSink.getValidationResults();
        assertTrue((Boolean) validationResults.get("hasDocument"));
        assertEquals("test-doc-1", validationResults.get("documentId"));
        
        // Step 4: Check pipeline statistics
        StepVerifier.create(pipelineExecutionService.getPipelineStatistics(TEST_PIPELINE_ID))
            .assertNext(stats -> {
                assertEquals(1, stats.documentsProcessed());
                assertEquals(1, stats.documentsSucceeded());
                assertEquals(0, stats.documentsFailed());
                assertTrue(stats.averageProcessingTime().toMillis() > 0);
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle pipeline processing failure")
    void testPipelineProcessingFailure() throws Exception {
        // Register the test sink module (reuse from setup)
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
            .setImplementationId(TEST_SINK_MODULE_ID)
            .setInstanceServiceName("Test Sink Service")
            .setModuleSoftwareVersion("1.0.0")
            .setHealthCheckType(HealthCheckType.GRPC)
            .setHealthCheckEndpoint("/health")
            .setHost("localhost")
            .setPort(grpcPort)
            .build();
        
        StepVerifier.create(registrationService.registerModule(request))
            .assertNext(status -> assertTrue(status.getSuccess()))
            .verifyComplete();
        
        // Create and register pipeline
        PipelineConfig pipelineConfig = createSingleStepPipeline();
        StepVerifier.create(pipelineExecutionService.createOrUpdatePipeline(TEST_PIPELINE_ID, pipelineConfig))
            .expectNext(true)
            .verifyComplete();
        
        // Configure sink to fail
        testSink.setShouldSucceed(false);
        testSink.setErrorMessage("Simulated processing failure");
        testSink.setExpectedMessageCount(1);
        
        // Process a document
        PipeDoc testDoc = PipeDoc.newBuilder()
            .setId("test-doc-fail")
            .setTitle("Test Document for Failure")
            .setBody("This document will fail processing")
            .build();
        
        // Process should complete but with error in stream
        StepVerifier.create(pipelineExecutionService.processDocument(TEST_PIPELINE_ID, testDoc))
            .assertNext(processedDoc -> {
                assertNotNull(processedDoc);
                // Document should still be returned even if processing failed
                assertEquals("test-doc-fail", processedDoc.getId());
            })
            .verifyComplete();
        
        // Wait for the sink to receive the message
        assertTrue(testSink.awaitMessages(5, TimeUnit.SECONDS));
        
        // Verify failure was recorded in statistics
        StepVerifier.create(pipelineExecutionService.getPipelineStatistics(TEST_PIPELINE_ID))
            .assertNext(stats -> {
                assertEquals(1, stats.documentsProcessed());
                assertEquals(0, stats.documentsSucceeded());
                assertEquals(1, stats.documentsFailed());
            })
            .verifyComplete();
    }
    
    @Test
    @DisplayName("Should process multiple documents through pipeline")
    void testMultipleDocumentProcessing() throws Exception {
        // Register module and create pipeline (reuse setup)
        RegisterModuleRequest request = RegisterModuleRequest.newBuilder()
            .setImplementationId(TEST_SINK_MODULE_ID)
            .setInstanceServiceName("Test Sink Service")
            .setModuleSoftwareVersion("1.0.0")
            .setHealthCheckType(HealthCheckType.GRPC)
            .setHealthCheckEndpoint("/health")
            .setHost("localhost")
            .setPort(grpcPort)
            .build();
        
        StepVerifier.create(registrationService.registerModule(request))
            .assertNext(status -> assertTrue(status.getSuccess()))
            .verifyComplete();
        
        PipelineConfig pipelineConfig = createSingleStepPipeline();
        StepVerifier.create(pipelineExecutionService.createOrUpdatePipeline(TEST_PIPELINE_ID, pipelineConfig))
            .expectNext(true)
            .verifyComplete();
        
        // Prepare multiple documents
        int docCount = 5;
        testSink.setExpectedMessageCount(docCount);
        
        // Process multiple documents
        for (int i = 0; i < docCount; i++) {
            PipeDoc doc = PipeDoc.newBuilder()
                .setId("test-doc-" + i)
                .setTitle("Test Document " + i)
                .setBody("Content for document " + i)
                .build();
            
            StepVerifier.create(pipelineExecutionService.processDocument(TEST_PIPELINE_ID, doc))
                .assertNext(processedDoc -> assertNotNull(processedDoc))
                .verifyComplete();
        }
        
        // Wait for all messages
        assertTrue(testSink.awaitMessages(10, TimeUnit.SECONDS));
        
        // Verify all documents were processed
        assertEquals(docCount, testSink.getProcessedCount());
        
        // Check statistics
        StepVerifier.create(pipelineExecutionService.getPipelineStatistics(TEST_PIPELINE_ID))
            .assertNext(stats -> {
                assertEquals(docCount, stats.documentsProcessed());
                assertEquals(docCount, stats.documentsSucceeded());
                assertEquals(0, stats.documentsFailed());
            })
            .verifyComplete();
    }
    
    private PipelineConfig createSingleStepPipeline() {
        // Create a simple pipeline with one step that goes to our test sink
        PipelineStepConfig sinkStep = new PipelineStepConfig(
            "sink-step",
            StepType.SINK,
            new PipelineStepConfig.ProcessorInfo(TEST_SINK_MODULE_ID, null),
            new PipelineStepConfig.JsonConfigOptions(Map.of("validation", "enabled"))
        );
        
        Map<String, PipelineStepConfig> steps = new TreeMap<>();
        steps.put("sink-step", sinkStep);
        
        return new PipelineConfig(TEST_PIPELINE_ID, steps);
    }
}