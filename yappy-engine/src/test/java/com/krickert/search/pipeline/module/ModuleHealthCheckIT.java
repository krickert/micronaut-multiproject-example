package com.krickert.search.pipeline.module;

import com.krickert.search.sdk.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for module health checking functionality.
 * Tests health monitoring using real Consul and gRPC services.
 */
@MicronautTest(environments = {"test"})
@Property(name = "yappy.module.discovery.enabled", value = "true")
@Property(name = "yappy.module.health.check.enabled", value = "true")
@Property(name = "yappy.module.health.check.interval", value = "1s")
@Property(name = "yappy.module.health.check.timeout", value = "2s")
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "consul.client.registration.enabled", value = "false")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModuleHealthCheckIT {
    
    private static final Logger log = LoggerFactory.getLogger(ModuleHealthCheckIT.class);
    
    @Inject
    ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    ModuleHealthMonitor moduleHealthMonitor;
    
    @Inject
    ModuleTestHelper testHelper;
    
    private HealthCheckableModule healthModule;
    private ModuleTestHelper.RegisteredModule registeredModule;
    
    @BeforeAll
    void setUpClass() throws Exception {
        // Clean up any previous test data
        testHelper.cleanupAllTestData();
        
        // Create a module with configurable health
        healthModule = new HealthCheckableModule();
        
        // Register the health check module
        registeredModule = testHelper.registerTestModule(
                "health-check-module",
                "health-module",
                healthModule,
                List.of("health-test", "yappy-module")
        );
        
        // Wait for Consul registration
        testHelper.waitForServiceDiscovery("health-check-module", 5000);
        
        // Discover modules
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(1000); // Wait for async discovery
        
        log.info("Health check module registered on port {}", registeredModule.getPort());
    }
    
    @AfterAll
    void tearDownClass() {
        testHelper.cleanupAllTestData();
    }
    
    @Test
    @Order(1)
    void testHealthCheckPassesWhenModuleHealthy() {
        // Ensure module is healthy
        healthModule.setHealthy(true);
        
        // Perform health check
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> {
                    log.info("Health check result: healthy={}, error={}", 
                            health.isHealthy(), health.getError());
                    return health.isHealthy();
                })
                .verifyComplete();
        
        // Verify module status is updated
        var moduleInfo = moduleDiscoveryService.getModuleInfo("health-check-module");
        assertNotNull(moduleInfo);
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, moduleInfo.status());
        assertNotNull(moduleInfo.lastHealthCheck());
    }
    
    @Test
    @Order(2)
    void testHealthCheckFailsWhenModuleUnhealthy() {
        // Make module unhealthy
        healthModule.setHealthy(false);
        
        // Perform health check
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> {
                    log.info("Health check result: healthy={}, error={}", 
                            health.isHealthy(), health.getError());
                    return !health.isHealthy() && health.getError() != null;
                })
                .verifyComplete();
    }
    
    @Test
    @Order(3)
    void testHealthCheckTimeoutHandling() {
        // Make module delay response
        healthModule.setResponseDelayMs(3000); // Longer than timeout
        
        // Perform health check - should timeout
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> {
                    log.info("Health check result after timeout: healthy={}, error={}", 
                            health.isHealthy(), health.getError());
                    return !health.isHealthy();
                })
                .verifyComplete();
        
        // Reset delay
        healthModule.setResponseDelayMs(0);
    }
    
    @Test
    @Order(4)
    void testPeriodicHealthCheckMonitoring() throws InterruptedException {
        // Reset health check counter
        healthModule.resetHealthCheckCount();
        healthModule.setHealthy(true);
        
        // Start monitoring
        moduleHealthMonitor.startMonitoring("health-check-module");
        
        // Wait for multiple health checks (3 seconds = 3 checks at 1s interval)
        Thread.sleep(3500);
        
        // Stop monitoring
        moduleHealthMonitor.stopMonitoring("health-check-module");
        
        // Verify multiple health checks occurred
        int checkCount = healthModule.getHealthCheckCount();
        log.info("Health checks performed: {}", checkCount);
        assertTrue(checkCount >= 3, "Expected at least 3 health checks, got " + checkCount);
    }
    
    @Test
    @Order(5)
    void testModuleRecoveryAfterFailure() throws InterruptedException {
        // Start with unhealthy module
        healthModule.setHealthy(false);
        
        // Check health - should fail
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> !health.isHealthy())
                .verifyComplete();
        
        // Verify module is marked as failed
        var moduleInfo = moduleDiscoveryService.getModuleInfo("health-check-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.FAILED, moduleInfo.status());
        
        // Make module healthy again
        healthModule.setHealthy(true);
        
        // Trigger discovery to update status
        moduleDiscoveryService.discoverAndRegisterModules();
        Thread.sleep(1000);
        
        // Check health again - should pass
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> health.isHealthy())
                .verifyComplete();
        
        // Verify module is marked as ready again
        moduleInfo = moduleDiscoveryService.getModuleInfo("health-check-module");
        assertEquals(ModuleDiscoveryService.ModuleStatusEnum.READY, moduleInfo.status());
    }
    
    @Test
    @Order(6)
    void testHealthCheckWithConnectionFailure() {
        // Deregister the module to simulate connection failure
        testHelper.markServiceUnhealthy(registeredModule.getServiceId());
        
        // Health check should handle gracefully
        StepVerifier.create(moduleHealthMonitor.checkModuleHealth("health-check-module"))
                .expectNextMatches(health -> !health.isHealthy())
                .verifyComplete();
        
        // Re-register as healthy
        testHelper.markServiceHealthy(registeredModule.getServiceId());
    }
    
    /**
     * Test module with configurable health status.
     */
    private static class HealthCheckableModule extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {
        private final AtomicBoolean healthy = new AtomicBoolean(true);
        private final AtomicInteger healthCheckCount = new AtomicInteger(0);
        private volatile int responseDelayMs = 0;
        
        void setHealthy(boolean isHealthy) {
            this.healthy.set(isHealthy);
        }
        
        void setResponseDelayMs(int delayMs) {
            this.responseDelayMs = delayMs;
        }
        
        int getHealthCheckCount() {
            return healthCheckCount.get();
        }
        
        void resetHealthCheckCount() {
            healthCheckCount.set(0);
        }
        
        @Override
        public void checkHealth(com.google.protobuf.Empty request,
                StreamObserver<HealthCheckResponse> responseObserver) {
            healthCheckCount.incrementAndGet();
            
            // Simulate delay if configured
            if (responseDelayMs > 0) {
                try {
                    Thread.sleep(responseDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            
            if (healthy.get()) {
                HealthCheckResponse response = HealthCheckResponse.newBuilder()
                        .setHealthy(true)
                        .setMessage("Module is healthy")
                        .setVersion("1.0.0")
                        .putDetails("checkCount", String.valueOf(healthCheckCount.get()))
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } else {
                // Simulate unhealthy state
                responseObserver.onError(new StatusRuntimeException(
                        Status.UNAVAILABLE.withDescription("Module is unhealthy")));
            }
        }
        
        @Override
        public void getServiceRegistration(com.google.protobuf.Empty request,
                StreamObserver<ServiceMetadata> responseObserver) {
            ServiceMetadata metadata = ServiceMetadata.newBuilder()
                    .setPipeStepName("health-module")
                    .putContextParams("description", "Health check test module")
                    .putContextParams("version", "1.0.0")
                    .build();
            
            responseObserver.onNext(metadata);
            responseObserver.onCompleted();
        }
        
        @Override
        public void processData(ProcessRequest request,
                StreamObserver<ProcessResponse> responseObserver) {
            ProcessResponse response = ProcessResponse.newBuilder()
                    .setSuccess(healthy.get())
                    .addProcessorLogs("Health module processed: " + request.getDocument().getId())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
}