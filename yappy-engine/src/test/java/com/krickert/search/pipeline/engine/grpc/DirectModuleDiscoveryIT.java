package com.krickert.search.pipeline.engine.grpc;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.pipeline.grpc.client.LocalServicesConfig;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import com.krickert.search.sdk.*;
import com.google.protobuf.Empty;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.discovery.DiscoveryClient;
import io.micronaut.discovery.ServiceInstance;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for direct module discovery approach.
 * Tests that the engine can discover and use modules directly without engine-to-engine proxying.
 */
@MicronautTest(environments = {"test"})
@Property(name = "grpc.server.port", value = "0") // Random port for test
class DirectModuleDiscoveryIT {
    
    @Inject
    PipeStreamEngineImpl pipeStreamEngine;
    
    @Inject
    ServiceStatusAggregator serviceStatusAggregator;
    
    @MockBean(DynamicConfigurationManager.class)
    DynamicConfigurationManager mockConfigManager() {
        return mock(DynamicConfigurationManager.class);
    }
    
    @MockBean(DiscoveryClient.class)
    DiscoveryClient mockDiscoveryClient() {
        return mock(DiscoveryClient.class);
    }
    
    @MockBean(LocalServicesConfig.class)
    LocalServicesConfig mockLocalServicesConfig() {
        LocalServicesConfig config = mock(LocalServicesConfig.class);
        when(config.getPorts()).thenReturn(new HashMap<>());
        return config;
    }
    
    @MockBean(PipeStreamEngine.class)
    PipeStreamEngine mockCoreEngine() {
        return mock(PipeStreamEngine.class);
    }
    
    @Inject
    DynamicConfigurationManager configManager;
    
    @Inject
    DiscoveryClient discoveryClient;
    
    @Inject
    LocalServicesConfig localServicesConfig;
    
    @Inject
    PipeStreamEngine coreEngine;
    
    private Server testModuleServer;
    private int testModulePort;
    private TestModule testModule;
    
    @BeforeEach
    void setUp() throws IOException {
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            testModulePort = socket.getLocalPort();
        }
        
        // Create and start test module server
        testModule = new TestModule();
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
    void testLocalModuleDiscovery() throws InterruptedException {
        // Setup: Configure local services to include our test module
        Map<String, Integer> localPorts = new HashMap<>();
        localPorts.put("test-module", testModulePort);
        when(localServicesConfig.getPorts()).thenReturn(localPorts);
        
        // Setup: Configure pipeline configuration
        PipelineClusterConfig clusterConfig = createTestClusterConfig();
        when(configManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        
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
        
        // Verify: Core engine was called
        verify(coreEngine).processStream(request);
        
        // Verify: Service status was NOT updated to proxying (since it's local)
        verify(serviceStatusAggregator, never()).updateServiceStatusToProxying(anyString());
    }
    
    @Test
    void testRemoteModuleDiscovery() throws InterruptedException {
        // Setup: No local services available
        when(localServicesConfig.getPorts()).thenReturn(new HashMap<>());
        
        // Setup: Configure discovery client to return remote instance
        ServiceInstance remoteInstance = mock(ServiceInstance.class);
        when(remoteInstance.getHost()).thenReturn("localhost");
        when(remoteInstance.getPort()).thenReturn(testModulePort);
        // ServiceInstance doesn't have getUri() method, only getHost() and getPort()
        
        when(discoveryClient.getInstances("test-module"))
                .thenReturn(Mono.just(List.of(remoteInstance)));
        
        // Setup: Configure pipeline configuration
        PipelineClusterConfig clusterConfig = createTestClusterConfig();
        when(configManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        
        // Create test request
        PipeStream request = PipeStream.newBuilder()
                .setStreamId("test-stream-2")
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-2")
                        .setBody("Test document for remote")
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
        
        // Verify: Core engine was called
        verify(coreEngine).processStream(request);
        
        // Verify: Service status WAS updated to proxying (since it's remote)
        verify(serviceStatusAggregator).updateServiceStatusToProxying("test-module");
    }
    
    @Test
    void testModuleNotAvailable() throws InterruptedException {
        // Setup: No local services and no remote instances
        when(localServicesConfig.getPorts()).thenReturn(new HashMap<>());
        when(discoveryClient.getInstances(anyString())).thenReturn(Mono.just(List.of()));
        
        // Setup: Configure pipeline configuration
        PipelineClusterConfig clusterConfig = createTestClusterConfig();
        when(configManager.getCurrentPipelineClusterConfig()).thenReturn(Optional.of(clusterConfig));
        
        // Setup: Core engine throws exception when module not available
        doThrow(new RuntimeException("Module not available: test-module"))
                .when(coreEngine).processStream(any());
        
        // Create test request
        PipeStream request = PipeStream.newBuilder()
                .setStreamId("test-stream-3")
                .setCurrentPipelineName("test-pipeline")
                .setTargetStepName("test-step")
                .setDocument(PipeDoc.newBuilder()
                        .setId("doc-3")
                        .setBody("Test document for unavailable")
                        .build())
                .build();
        
        // Test: Process request
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        
        pipeStreamEngine.processPipeAsync(request, new StreamObserver<Empty>() {
            @Override
            public void onNext(Empty value) {
                fail("Should not succeed");
            }
            
            @Override
            public void onError(Throwable t) {
                error.set(t);
                latch.countDown();
            }
            
            @Override
            public void onCompleted() {
                fail("Should not complete successfully");
            }
        });
        
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Request should complete within 5 seconds");
        assertNotNull(error.get(), "Should have error");
        assertTrue(error.get().getMessage().contains("Failed to process pipe"),
                "Error message should indicate processing failure");
    }
    
    private PipelineClusterConfig createTestClusterConfig() {
        // Create module configuration
        PipelineModuleConfiguration moduleConfig = new PipelineModuleConfiguration(
                "Test Module",
                "test-module",
                null,
                null
        );
        
        // Create pipeline module map
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        modules.put("test-module", moduleConfig);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);
        
        // Create processor info
        ProcessorInfo processorInfo = new ProcessorInfo(
                "test-module",
                null
        );
        
        // Create step configuration
        PipelineStepConfig stepConfig = new PipelineStepConfig(
                "test-step",
                StepType.PIPELINE,
                processorInfo
        );
        
        // Create pipeline configuration
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        steps.put("test-step", stepConfig);
        PipelineConfig pipelineConfig = new PipelineConfig(
                "test-pipeline",
                steps
        );
        
        // Create pipeline graph config
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("test-pipeline", pipelineConfig);
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        
        // Create and return cluster config
        return new PipelineClusterConfig(
                "test-cluster",
                graphConfig,
                moduleMap,
                "kafka-cluster-id",
                Set.of(),
                Set.of()
        );
    }
    
    /**
     * Simple test module implementation.
     */
    private static class TestModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        
        @Override
        public void getServiceRegistration(Empty request, StreamObserver<ServiceMetadata> responseObserver) {
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("test-module")
                    .putContextParams("description", "Test module for integration testing")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .setOutputDoc(request.getDocument())
                    .addProcessorLogs("Processed by test module")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        
        @Override
        public void checkHealth(Empty request, StreamObserver<HealthCheckResponse> responseObserver) {
            HealthCheckResponse response = HealthCheckResponse.newBuilder()
                    .setHealthy(true)
                    .setMessage("Test module is healthy")
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}