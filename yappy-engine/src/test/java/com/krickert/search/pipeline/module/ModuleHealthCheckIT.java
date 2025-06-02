package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for module health checking functionality.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "yappy.module.health.check.enabled", value = "true")
@Property(name = "yappy.module.health.check.interval", value = "1s")
@Property(name = "yappy.module.health.check.timeout", value = "2s")
class ModuleHealthCheckIT {
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleHealthMonitor moduleHealthMonitor;
    
    @MockBean(ConsulBusinessOperationsService.class)
    ConsulBusinessOperationsService consulService() {
        return mock(ConsulBusinessOperationsService.class);
    }
    
    @MockBean(GrpcChannelManager.class)
    GrpcChannelManager channelManager() {
        return mock(GrpcChannelManager.class);
    }
    
    @Inject
    ConsulBusinessOperationsService mockConsulService;
    
    @Inject
    GrpcChannelManager mockChannelManager;
    
    private Server grpcServer;
    private ManagedChannel testChannel;
    private int serverPort;
    private HealthCheckableModule healthModule;
    
    @BeforeEach
    void setUp() throws IOException {
        // Find available port
        try (ServerSocket socket = new ServerSocket(0)) {
            serverPort = socket.getLocalPort();
        }
        
        // Create a test module with configurable health
        healthModule = new HealthCheckableModule();
        grpcServer = ServerBuilder
                .forPort(serverPort)
                .addService(healthModule)
                .build()
                .start();
        
        testChannel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", serverPort)
                .usePlaintext()
                .build();
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        if (testChannel != null) {
            testChannel.shutdown();
            testChannel.awaitTermination(5, TimeUnit.SECONDS);
        }
        
        if (grpcServer != null) {
            grpcServer.shutdown();
            grpcServer.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
    
    @Test
    void testHealthCheckPassesWhenModuleHealthy() throws InterruptedException {
        // Setup
        healthModule.setHealthy(true);
        setupMockDiscovery("health-test-module");
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Verify initial state
        assertTrue(moduleDiscoveryService.isModuleAvailable("health-test-module"));
        ModuleDiscoveryService.ModuleInfo info = moduleDiscoveryService.getModuleInfo("health-test-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, info.status());
        
        // Perform health check
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-test-module"))
                .assertNext(result -> {
                    assertTrue(result.isHealthy());
                    assertEquals("health-test-module", result.getModuleName());
                    assertNotNull(result.getResponseTime());
                })
                .verifyComplete();
        
        // Wait for async health check update
        Thread.sleep(500);
        
        // Verify module is still ready
        info = moduleDiscoveryService.getModuleInfo("health-test-module");
        assertEquals(ModuleDiscoveryService.ModuleStatus.READY, info.status());
        assertNotNull(info.lastHealthCheck());
    }
    
    @Test
    void testHealthCheckFailsWhenModuleUnhealthy() throws InterruptedException {
        // Setup
        healthModule.setHealthy(false);
        setupMockDiscovery("unhealthy-module");
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Verify initial state
        assertTrue(moduleDiscoveryService.isModuleAvailable("unhealthy-module"));
        
        // Perform health check
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("unhealthy-module"))
                .assertNext(result -> {
                    assertFalse(result.isHealthy());
                    assertEquals("unhealthy-module", result.getModuleName());
                    assertNotNull(result.getError());
                })
                .verifyComplete();
        
        // Wait for async status update
        Thread.sleep(500);
        
        // Verify module status changed
        ModuleDiscoveryService.ModuleInfo info = moduleDiscoveryService.getModuleInfo("unhealthy-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.UNHEALTHY, info.status());
    }
    
    @Test
    void testHealthCheckTimeoutHandling() throws InterruptedException {
        // Setup module that doesn't respond to health checks
        healthModule.setRespondToHealthCheck(false);
        setupMockDiscovery("timeout-module");
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Perform health check (should timeout)
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("timeout-module"))
                .expectTimeout(Duration.ofSeconds(3))
                .verify();
    }
    
    @Test
    void testPeriodicHealthCheckMonitoring() throws InterruptedException {
        // Setup
        healthModule.setHealthy(true);
        setupMockDiscovery("monitored-module");
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Start monitoring
        moduleHealthMonitor.startMonitoring("monitored-module");
        
        // Verify health checks are happening
        int initialCount = healthModule.getHealthCheckCount();
        Thread.sleep(2500); // Wait for at least 2 health check intervals
        
        int finalCount = healthModule.getHealthCheckCount();
        assertTrue(finalCount >= initialCount + 2, 
                "Expected at least 2 health checks, but got " + (finalCount - initialCount));
        
        // Stop monitoring
        moduleHealthMonitor.stopMonitoring("monitored-module");
        Thread.sleep(1500);
        
        // Verify no more health checks
        int afterStopCount = healthModule.getHealthCheckCount();
        Thread.sleep(1500);
        assertEquals(afterStopCount, healthModule.getHealthCheckCount(), 
                "Health checks should stop after monitoring is stopped");
    }
    
    @Test
    void testModuleRecoveryAfterFailure() throws InterruptedException {
        // Setup
        healthModule.setHealthy(true);
        setupMockDiscovery("recovery-module");
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Start monitoring
        moduleHealthMonitor.startMonitoring("recovery-module");
        
        // Verify initial healthy state
        ModuleDiscoveryService.ModuleInfo info = moduleDiscoveryService.getModuleInfo("recovery-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, info.status());
        
        // Make module unhealthy
        healthModule.setHealthy(false);
        Thread.sleep(1500); // Wait for health check to detect failure
        
        // Verify unhealthy state
        info = moduleDiscoveryService.getModuleInfo("recovery-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.UNHEALTHY, info.status());
        
        // Make module healthy again
        healthModule.setHealthy(true);
        Thread.sleep(1500); // Wait for health check to detect recovery
        
        // Verify recovery
        info = moduleDiscoveryService.getModuleInfo("recovery-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, info.status());
        
        // Stop monitoring
        moduleHealthMonitor.stopMonitoring("recovery-module");
    }
    
    // Helper methods
    
    private void setupMockDiscovery(String serviceName) {
        Map<String, List<String>> services = new HashMap<>();
        services.put(serviceName, List.of("yappy-module"));
        
        when(mockConsulService.listServices()).thenReturn(Mono.just(services));
        when(mockConsulService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(List.of(createHealthyInstance())));
        when(mockChannelManager.getChannel(serviceName)).thenReturn(testChannel);
    }
    
    private org.kiwiproject.consul.model.health.ServiceHealth createHealthyInstance() {
        org.kiwiproject.consul.model.health.ServiceHealth health = 
                mock(org.kiwiproject.consul.model.health.ServiceHealth.class);
        org.kiwiproject.consul.model.health.Service service = 
                mock(org.kiwiproject.consul.model.health.Service.class);
        
        when(service.getAddress()).thenReturn("localhost");
        when(service.getPort()).thenReturn(serverPort);
        when(health.getService()).thenReturn(service);
        
        return health;
    }
    
    // Test module implementation
    
    private static class HealthCheckableModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final AtomicBoolean respondToHealthCheck = new AtomicBoolean(true);
        private final AtomicInteger healthCheckCount = new AtomicInteger(0);
        
        void setHealthy(boolean isHealthy) {
            healthy.set(isHealthy);
        }
        
        void setRespondToHealthCheck(boolean respond) {
            respondToHealthCheck.set(respond);
        }
        
        int getHealthCheckCount() {
            return healthCheckCount.get();
        }
        
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("health-checkable-module")
                    .putContextParams("description", "Module for health check testing")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            if (!healthy.get()) {
                responseObserver.onError(new StatusRuntimeException(
                        Status.UNAVAILABLE.withDescription("Module is unhealthy")));
                return;
            }
            
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .addProcessorLogs("Processing completed")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        
        @Override
        public void testMode(TestRequest request,
                StreamObserver<TestResponse> responseObserver) {
            healthCheckCount.incrementAndGet();
            
            if (!respondToHealthCheck.get()) {
                // Don't respond to simulate timeout
                return;
            }
            
            if (!healthy.get()) {
                responseObserver.onError(new StatusRuntimeException(
                        Status.UNAVAILABLE.withDescription("Health check failed")));
                return;
            }
            
            TestResponse response = TestResponse.newBuilder()
                    .setSuccess(true)
                    .putTestResults("status", "healthy")
                    .putTestResults("timestamp", String.valueOf(System.currentTimeMillis()))
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}