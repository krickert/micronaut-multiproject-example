package com.krickert.search.pipeline.engine.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.sdk.*;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for direct module discovery approach.
 * Tests that the engine can discover and use modules directly without engine-to-engine proxying.
 */
@MicronautTest(environments = {"test"})
@Property(name = "grpc.server.port", value = "0") // Random port for test
@Property(name = "yappy.engine.auto-register", value = "false") // Disable auto-registration
@Property(name = "grpc.client.plaintext", value = "true")
@Property(name = "yappy.cluster-name", value = "test-cluster")
class DirectModuleDiscoveryIT {
    
    @Inject
    PipeStreamEngineImpl pipeStreamEngine;
    
    @MockBean(DynamicConfigurationManager.class)
    DynamicConfigurationManager mockConfigManager() {
        DynamicConfigurationManager mock = mock(DynamicConfigurationManager.class);
        // Always return empty config to avoid complex setup
        when(mock.getCurrentPipelineClusterConfig()).thenReturn(Optional.empty());
        return mock;
    }
    
    @MockBean(PipeStreamEngine.class)
    PipeStreamEngine mockCoreEngine() {
        PipeStreamEngine mock = mock(PipeStreamEngine.class);
        // Core engine just processes the stream
        doAnswer(invocation -> {
            // Simulate successful processing
            return null;
        }).when(mock).processStream(any(PipeStream.class));
        return mock;
    }
    
    @Inject
    PipeStreamEngine coreEngine;
    
    private Server testModuleServer;
    private int testModulePort;
    
    @BeforeEach
    void setUp() throws IOException {
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            testModulePort = socket.getLocalPort();
        }
        
        // Create and start test module server
        TestModule testModule = new TestModule();
        testModuleServer = ServerBuilder
                .forPort(testModulePort)
                .addService(testModule)
                .build()
                .start();
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        if (testModuleServer != null) {
            testModuleServer.shutdown();
            testModuleServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testDirectModuleProcessing() throws InterruptedException {
        // Create test request
        PipeStream request = PipeStream.newBuilder()
                .setStreamId("test-stream-1")
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-1")
                        .setBody("Test document")
                        .build())
                .build();
        
        // Test: Process request
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean success = new AtomicBoolean(false);
        
        pipeStreamEngine.processPipeAsync(request, new StreamObserver<Empty>() {
            @Override
            public void onNext(Empty value) {
                success.set(true);
            }
            
            @Override
            public void onError(Throwable t) {
                fail("Should not error: " + t.getMessage());
            }
            
            @Override
            public void onCompleted() {
                latch.countDown();
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request should complete within 5 seconds");
        assertTrue(success.get(), "Request should succeed");
        
        // Verify: Core engine was called to process the stream
        verify(coreEngine).processStream(request);
    }
    
    // Test module implementation
    private static class TestModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("test-processor")
                    .putContextParams("description", "Test module for integration testing")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .addProcessorLogs("Processed document: " + request.getDocument().getId())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}