package com.krickert.search.pipeline.module;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.pipeline.grpc.client.GrpcChannelManager;
import com.krickert.search.sdk.*;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.kiwiproject.consul.model.health.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test for module failover mechanism.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "yappy.module.health.check.enabled", value = "true")
@Property(name = "yappy.module.failover.enabled", value = "true")
class ModuleFailoverIT {
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleHealthMonitor moduleHealthMonitor;
    
    @Inject
    ModuleFailoverManager moduleFailoverManager;
    
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
    
    private List<Server> grpcServers = new ArrayList<>();
    private List<ManagedChannel> testChannels = new ArrayList<>();
    private List<Integer> serverPorts = new ArrayList<>();
    private List<FailoverModule> modules = new ArrayList<>();
    
    @BeforeEach
    void setUp() throws IOException {
        // Create 3 module instances for failover testing
        for (int i = 0; i < 3; i++) {
            // Find available port
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
                serverPorts.add(port);
            }
            
            // Create a test module
            FailoverModule module = new FailoverModule("instance-" + i);
            modules.add(module);
            
            Server server = ServerBuilder
                    .forPort(port)
                    .addService(module)
                    .build()
                    .start();
            grpcServers.add(server);
            
            ManagedChannel channel = io.grpc.ManagedChannelBuilder
                    .forAddress("localhost", port)
                    .usePlaintext()
                    .build();
            testChannels.add(channel);
        }
    }
    
    @AfterEach
    void tearDown() throws InterruptedException {
        for (ManagedChannel channel : testChannels) {
            if (channel != null) {
                channel.shutdown();
                channel.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
        
        for (Server server : grpcServers) {
            if (server != null) {
                server.shutdown();
                server.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }
    
    @Test
    void testFailoverToHealthyInstance() throws InterruptedException {
        // Setup - configure consul to return all 3 instances
        String serviceName = "failover-module";
        List<ServiceHealth> healthyInstances = createHealthyInstances(serverPorts);
        
        when(mockConsulService.listServices())
                .thenReturn(Mono.just(Map.of(serviceName, List.of("yappy-module"))));
        when(mockConsulService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(healthyInstances));
        
        // Configure channel manager to return different channels based on call count
        when(mockChannelManager.getChannel(serviceName))
                .thenReturn(testChannels.get(0))
                .thenReturn(testChannels.get(1))
                .thenReturn(testChannels.get(2));
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Verify initial state
        assertTrue(moduleDiscoveryService.isModuleAvailable(serviceName));
        
        // Make first instance unhealthy
        modules.get(0).setHealthy(false);
        
        // Start monitoring to trigger failover
        moduleHealthMonitor.startMonitoring(serviceName);
        Thread.sleep(1500); // Wait for health check to detect failure
        
        // Perform failover
        boolean failoverSuccess = moduleFailoverManager.performFailover(serviceName);
        assertTrue(failoverSuccess, "Failover should succeed when healthy instances are available");
        
        // Verify module is still available (failover successful)
        assertTrue(moduleDiscoveryService.isModuleAvailable(serviceName));
        
        // Verify we're now using a different instance
        ModuleDiscoveryService.ModuleInfo info = moduleDiscoveryService.getModuleInfo(serviceName);
        assertNotNull(info);
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, info.status());
        
        // Stop monitoring
        moduleHealthMonitor.stopMonitoring(serviceName);
    }
    
    @Test
    void testFailoverWithNoHealthyInstances() throws InterruptedException {
        // Setup - configure consul to return all 3 instances
        String serviceName = "no-healthy-module";
        List<ServiceHealth> instances = createHealthyInstances(serverPorts);
        
        when(mockConsulService.listServices())
                .thenReturn(Mono.just(Map.of(serviceName, List.of("yappy-module"))));
        when(mockConsulService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(instances));
        when(mockChannelManager.getChannel(serviceName))
                .thenReturn(testChannels.get(0));
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Make all instances unhealthy
        modules.forEach(module -> module.setHealthy(false));
        
        // Perform failover
        boolean failoverSuccess = moduleFailoverManager.performFailover(serviceName);
        assertFalse(failoverSuccess, "Failover should fail when no healthy instances are available");
        
        // Verify module is marked as unavailable
        assertFalse(moduleDiscoveryService.isModuleAvailable(serviceName));
    }
    
    @Test
    void testAutomaticFailoverOnProcessingError() throws InterruptedException {
        // Setup
        String serviceName = "auto-failover-module";
        List<ServiceHealth> instances = createHealthyInstances(serverPorts);
        
        when(mockConsulService.listServices())
                .thenReturn(Mono.just(Map.of(serviceName, List.of("yappy-module"))));
        when(mockConsulService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(instances));
        when(mockChannelManager.getChannel(serviceName))
                .thenReturn(testChannels.get(0))
                .thenReturn(testChannels.get(1));
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Configure first instance to fail on processing
        modules.get(0).setFailOnProcess(true);
        
        // Enable automatic failover
        moduleFailoverManager.enableAutomaticFailover(serviceName);
        
        // Try to process data - should trigger automatic failover
        ModuleDiscoveryService.ModuleInfo info = moduleDiscoveryService.getModuleInfo(serviceName);
        assertNotNull(info);
        
        var testDoc = com.krickert.search.model.PipeDoc.newBuilder()
                .setId("test-" + System.currentTimeMillis())
                .setBody("Test content")
                .build();
        
        ProcessRequest request = ProcessRequest.newBuilder()
                .setDocument(testDoc)
                .build();
        
        // This should fail on first instance and trigger failover
        AtomicBoolean processingSucceeded = new AtomicBoolean(false);
        info.stub().processData(request, new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse response) {
                processingSucceeded.set(response.getSuccess());
            }
            
            @Override
            public void onError(Throwable t) {
                // Expected for first instance
            }
            
            @Override
            public void onCompleted() {
                // Processing completed
            }
        });
        
        // Wait for failover to happen
        Thread.sleep(1000);
        
        // Verify failover occurred
        assertTrue(moduleFailoverManager.hasFailedOver(serviceName));
        
        // Try processing again - should work with second instance
        info = moduleDiscoveryService.getModuleInfo(serviceName);
        info.stub().processData(request, new StreamObserver<ProcessResponse>() {
            @Override
            public void onNext(ProcessResponse response) {
                processingSucceeded.set(response.getSuccess());
            }
            
            @Override
            public void onError(Throwable t) {
                fail("Processing should succeed after failover");
            }
            
            @Override
            public void onCompleted() {
                // Processing completed
            }
        });
        
        Thread.sleep(500);
        assertTrue(processingSucceeded.get(), "Processing should succeed after failover");
    }
    
    @Test
    void testFailoverCircuitBreaker() throws InterruptedException {
        // Setup
        String serviceName = "circuit-breaker-module";
        List<ServiceHealth> instances = createHealthyInstances(serverPorts);
        
        when(mockConsulService.listServices())
                .thenReturn(Mono.just(Map.of(serviceName, List.of("yappy-module"))));
        when(mockConsulService.getHealthyServiceInstances(serviceName))
                .thenReturn(Mono.just(instances));
        when(mockChannelManager.getChannel(serviceName))
                .thenReturn(testChannels.get(0));
        
        // Discover module
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(500); // Wait for discovery
        
        // Make all instances unhealthy
        modules.forEach(module -> module.setHealthy(false));
        
        // Try multiple failovers - circuit breaker should kick in
        for (int i = 0; i < 5; i++) {
            moduleFailoverManager.performFailover(serviceName);
        }
        
        // Verify circuit breaker is open
        assertTrue(moduleFailoverManager.isCircuitBreakerOpen(serviceName),
                "Circuit breaker should open after multiple failed failover attempts");
        
        // Further failover attempts should be rejected immediately
        boolean failoverAttempt = moduleFailoverManager.performFailover(serviceName);
        assertFalse(failoverAttempt, "Failover should be rejected when circuit breaker is open");
    }
    
    // Helper methods
    
    private List<ServiceHealth> createHealthyInstances(List<Integer> ports) {
        List<ServiceHealth> instances = new ArrayList<>();
        for (int i = 0; i < ports.size(); i++) {
            ServiceHealth health = mock(ServiceHealth.class);
            Service service = mock(Service.class);
            
            when(service.getAddress()).thenReturn("localhost");
            when(service.getPort()).thenReturn(ports.get(i));
            when(service.getId()).thenReturn("instance-" + i);
            when(health.getService()).thenReturn(service);
            
            instances.add(health);
        }
        return instances;
    }
    
    // Test module implementation
    
    private static class FailoverModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        private final String instanceId;
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final AtomicBoolean failOnProcess = new AtomicBoolean(false);
        
        FailoverModule(String instanceId) {
            this.instanceId = instanceId;
        }
        
        void setHealthy(boolean isHealthy) {
            healthy.set(isHealthy);
        }
        
        void setFailOnProcess(boolean shouldFail) {
            failOnProcess.set(shouldFail);
        }
        
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("failover-test-module")
                    .putContextParams("instance_id", instanceId)
                    .putContextParams("description", "Module for failover testing")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            if (failOnProcess.get()) {
                responseObserver.onError(new StatusRuntimeException(
                        io.grpc.Status.UNAVAILABLE.withDescription("Instance failed")));
                return;
            }
            
            if (!healthy.get()) {
                responseObserver.onError(new StatusRuntimeException(
                        io.grpc.Status.UNAVAILABLE.withDescription("Module is unhealthy")));
                return;
            }
            
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(true)
                    .addProcessorLogs("Processed by instance: " + instanceId)
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
        
        @Override
        public void checkHealth(com.google.protobuf.Empty request,
                StreamObserver<com.krickert.search.sdk.HealthCheckResponse> responseObserver) {
            if (!healthy.get()) {
                responseObserver.onError(new StatusRuntimeException(
                        io.grpc.Status.UNAVAILABLE.withDescription("Health check failed")));
                return;
            }
            
            var response = com.krickert.search.sdk.HealthCheckResponse.newBuilder()
                    .setHealthy(true)
                    .setMessage("Module is healthy")
                    .setVersion("1.0.0")
                    .putDetails("instance", instanceId)
                    .putDetails("status", "healthy")
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}