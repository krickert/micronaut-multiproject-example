package com.krickert.search.engine.core;

import com.ecwid.consul.v1.ConsulClient;
import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.google.protobuf.Empty;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for PipelineEngineImpl with service discovery.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineEngineImplTest {
    
    private static final Logger logger = LoggerFactory.getLogger(PipelineEngineImplTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    private ConsulClient consulClient;
    private String testClusterName;
    private PipelineEngineImpl pipelineEngine;
    private Server mockChunkerServer;
    private Server mockTikaServer;
    
    @BeforeAll
    void setup() throws IOException {
        // Get Consul configuration
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse("localhost");
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class).orElse(8500);
        consulClient = new ConsulClient(consulHost, consulPort);
        
        // Create test cluster
        testClusterName = TestClusterHelper.createTestCluster("pipeline-engine-test");
        
        // Create pipeline engine for this cluster
        DynamicConfigurationManager configManager = applicationContext.getBean(DynamicConfigurationManager.class);
        pipelineEngine = new PipelineEngineImpl(
            consulClient, 
            configManager,
            testClusterName,
            false,  // Disable buffer for this test
            100,   // Default capacity
            3,     // Default precision
            0.1    // Default sample rate
        );
        
        // Start mock services
        startMockServices();
        
        // Register mock services in Consul
        registerMockServices();
    }
    
    @AfterAll
    void cleanup() throws InterruptedException {
        // Shutdown servers
        if (mockChunkerServer != null) {
            mockChunkerServer.shutdown();
            mockChunkerServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (mockTikaServer != null) {
            mockTikaServer.shutdown();
            mockTikaServer.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        // Cleanup pipeline engine
        if (pipelineEngine != null) {
            pipelineEngine.shutdown();
        }
        
        // Cleanup test cluster
        if (consulClient != null && testClusterName != null) {
            TestClusterHelper.cleanupTestCluster(consulClient, testClusterName);
        }
    }
    
    @Test
    void testSingleStepPipeline() {
        // Create a simple pipeline with one step
        var step = new PipelineEngineImpl.TestStep(
            "chunker",
            "test-doc-1",
            "This is a test document that needs to be chunked."
        );
        
        // Execute pipeline
        StepVerifier.create(pipelineEngine.executePipelineTest("test-single-step", List.of(step)))
            .expectNext(true)
            .verifyComplete();
    }
    
    @Test
    void testMultiStepPipeline() {
        // Create a pipeline with multiple steps
        var tikaStep = new PipelineEngineImpl.TestStep(
            "tika-parser",
            "test-doc-2",
            "Parse this PDF content"
        );
        
        var chunkerStep = new PipelineEngineImpl.TestStep(
            "chunker",
            "test-doc-2",
            "Chunk the parsed content"
        );
        
        // Execute pipeline
        StepVerifier.create(pipelineEngine.executePipelineTest("test-multi-step", 
            List.of(tikaStep, chunkerStep)))
            .expectNext(true)
            .verifyComplete();
    }
    
    @Test
    void testPipelineWithFailingStep() {
        // Create a pipeline with a step that will fail
        var failingStep = new PipelineEngineImpl.TestStep(
            "chunker",
            "fail-doc",
            "FAIL_ME"  // Special content that triggers failure
        );
        
        // Execute pipeline - should return false
        StepVerifier.create(pipelineEngine.executePipelineTest("test-failing-pipeline", 
            List.of(failingStep)))
            .expectNext(false)
            .verifyComplete();
    }
    
    @Test
    void testServiceDiscoveryFailure() {
        // Create a step for a non-existent service
        var missingServiceStep = new PipelineEngineImpl.TestStep(
            "non-existent-service",
            "test-doc",
            "Test content"
        );
        
        // Should fail with service not found
        StepVerifier.create(pipelineEngine.executePipelineTest("test-missing-service", 
            List.of(missingServiceStep)))
            .expectError(IllegalStateException.class)
            .verify();
    }
    
    private void startMockServices() throws IOException {
        // Start mock chunker service
        mockChunkerServer = ServerBuilder.forPort(0)  // Random port
            .addService(new MockPipeStepProcessor("chunker"))
            .build()
            .start();
        
        logger.info("Started mock chunker on port {}", mockChunkerServer.getPort());
        
        // Start mock tika parser service
        mockTikaServer = ServerBuilder.forPort(0)  // Random port
            .addService(new MockPipeStepProcessor("tika-parser"))
            .build()
            .start();
        
        logger.info("Started mock tika-parser on port {}", mockTikaServer.getPort());
    }
    
    private void registerMockServices() {
        // Register chunker
        TestClusterHelper.registerServiceInCluster(
            consulClient,
            testClusterName,
            "chunker",
            "chunker-test-1",
            "localhost",
            mockChunkerServer.getPort(),
            Map.of("module-type", "text-processor", "test-service", "true")
        );
        
        // Register tika-parser
        TestClusterHelper.registerServiceInCluster(
            consulClient,
            testClusterName,
            "tika-parser",
            "tika-test-1",
            "localhost",
            mockTikaServer.getPort(),
            Map.of("module-type", "document-parser", "test-service", "true")
        );
        
        // Wait for services to be registered and verify
        try {
            Thread.sleep(1000);
            
            // Verify services are registered
            var chunkerServices = consulClient.getHealthServices(
                testClusterName + "-chunker", false, null).getValue();
            logger.info("Found {} chunker services in cluster {}", chunkerServices.size(), testClusterName);
            
            var tikaServices = consulClient.getHealthServices(
                testClusterName + "-tika-parser", false, null).getValue();
            logger.info("Found {} tika-parser services in cluster {}", tikaServices.size(), testClusterName);
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Mock implementation of PipeStepProcessor for testing.
     */
    static class MockPipeStepProcessor extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        
        private final String serviceName;
        
        MockPipeStepProcessor(String serviceName) {
            this.serviceName = serviceName;
        }
        
        @Override
        public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
            logger.info("{} processing document: {}", serviceName, request.getDocument().getId());
            
            // Simulate failure for specific content
            boolean success = !request.getDocument().getBody().equals("FAIL_ME");
            
            // Create output document
            com.krickert.search.model.PipeDoc outputDoc = com.krickert.search.model.PipeDoc.newBuilder()
                .setId(request.getDocument().getId())
                .setBody(success ? 
                    "Processed by " + serviceName + ": " + request.getDocument().getBody() : 
                    request.getDocument().getBody())
                .build();
            
            ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder()
                .setSuccess(success);
                
            if (success) {
                responseBuilder.setOutputDoc(outputDoc);
            } else {
                // Add error details using Struct
                com.google.protobuf.Struct.Builder errorStruct = com.google.protobuf.Struct.newBuilder();
                errorStruct.putFields("error", com.google.protobuf.Value.newBuilder()
                    .setStringValue("Simulated failure").build());
                responseBuilder.setErrorDetails(errorStruct);
            }
            
            responseBuilder.addProcessorLogs("Processed by " + serviceName);
            
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }
        
        @Override
        public void getServiceRegistration(Empty request, StreamObserver<com.krickert.search.sdk.ServiceRegistrationData> responseObserver) {
            var registrationData = com.krickert.search.sdk.ServiceRegistrationData.newBuilder()
                .setModuleName(serviceName)
                .build();
            
            responseObserver.onNext(registrationData);
            responseObserver.onCompleted();
        }
    }
}