package com.krickert.search.engine.health.impl;

import com.krickert.search.engine.health.ModuleHealthMonitor;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.yappy.registration.api.HealthCheckType;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class ModuleHealthMonitorImplTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleHealthMonitorImplTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    private ModuleHealthMonitor healthMonitor;
    private static Server grpcServer;
    private static int grpcPort;
    private static MockHealthService mockHealthService;
    
    @BeforeAll
    static void setupGrpcServer() throws IOException {
        // Find a free port
        try (ServerSocket socket = new ServerSocket(0)) {
            grpcPort = socket.getLocalPort();
        }
        
        mockHealthService = new MockHealthService();
        grpcServer = ServerBuilder.forPort(grpcPort)
                .addService(mockHealthService)
                .build()
                .start();
        
        LOG.info("Started mock gRPC server on port {}", grpcPort);
    }
    
    @AfterAll
    static void teardownGrpcServer() {
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
        healthMonitor = applicationContext.getBean(ModuleHealthMonitor.class);
        mockHealthService.reset();
    }
    
    @Test
    @DisplayName("Should start and stop monitoring a module")
    void testStartStopMonitoring() {
        ModuleInfo moduleInfo = createModuleInfo("test-module-1", "localhost", grpcPort);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        StepVerifier.create(healthMonitor.stopMonitoring("test-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should perform gRPC health check successfully")
    void testGrpcHealthCheckSuccess() throws InterruptedException {
        ModuleInfo moduleInfo = createModuleInfo("grpc-module-1", "localhost", grpcPort);
        mockHealthService.setHealthy(true);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        // Wait for initial health check
        Thread.sleep(1000);
        
        StepVerifier.create(healthMonitor.getCachedHealthStatus("grpc-module-1"))
                .assertNext(status -> {
                    assertTrue(status.getIsHealthy());
                    assertEquals("grpc-module-1", status.getServiceId());
                    assertEquals("test-service", status.getServiceName());
                })
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(healthMonitor.stopMonitoring("grpc-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should detect gRPC health check failure")
    void testGrpcHealthCheckFailure() throws InterruptedException {
        ModuleInfo moduleInfo = createModuleInfo("grpc-module-2", "localhost", grpcPort);
        mockHealthService.setHealthy(false);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        // Wait for health check
        Thread.sleep(1000);
        
        StepVerifier.create(healthMonitor.getCachedHealthStatus("grpc-module-2"))
                .assertNext(status -> {
                    assertFalse(status.getIsHealthy());
                    assertEquals("grpc-module-2", status.getServiceId());
                })
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(healthMonitor.stopMonitoring("grpc-module-2"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should perform TCP health check")
    void testTcpHealthCheck() throws InterruptedException {
        ModuleInfo moduleInfo = createModuleInfo("tcp-module-1", "localhost", grpcPort);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.TCP, ""))
                .verifyComplete();
        
        // Wait for health check
        Thread.sleep(1000);
        
        StepVerifier.create(healthMonitor.getCachedHealthStatus("tcp-module-1"))
                .assertNext(status -> {
                    assertTrue(status.getIsHealthy());
                    assertEquals("tcp-module-1", status.getServiceId());
                    assertTrue(status.getHealthDetails().contains("TCP connection successful"));
                })
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(healthMonitor.stopMonitoring("tcp-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle health status changes")
    void testHealthStatusChanges() throws InterruptedException {
        ModuleInfo moduleInfo = createModuleInfo("change-module-1", "localhost", grpcPort);
        mockHealthService.setHealthy(true);
        
        AtomicReference<ModuleHealthStatus> capturedNewStatus = new AtomicReference<>();
        AtomicReference<ModuleHealthStatus> capturedOldStatus = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        
        ModuleHealthMonitor.HealthChangeListener listener = (moduleId, newStatus, previousStatus) -> {
            capturedNewStatus.set(newStatus);
            capturedOldStatus.set(previousStatus);
            latch.countDown();
        };
        
        healthMonitor.addHealthChangeListener(listener);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        // Wait for initial health check
        Thread.sleep(1000);
        
        // Change health status
        mockHealthService.setHealthy(false);
        
        // Wait for health change notification
        assertTrue(latch.await(15, TimeUnit.SECONDS), "Health change notification not received");
        
        assertNotNull(capturedNewStatus.get());
        assertNotNull(capturedOldStatus.get());
        assertFalse(capturedNewStatus.get().getIsHealthy());
        assertTrue(capturedOldStatus.get().getIsHealthy());
        
        // Cleanup
        healthMonitor.removeHealthChangeListener(listener);
        StepVerifier.create(healthMonitor.stopMonitoring("change-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle consecutive failures")
    void testConsecutiveFailures() throws InterruptedException {
        ModuleInfo moduleInfo = createModuleInfo("failure-module-1", "localhost", grpcPort);
        
        // Start with healthy status
        mockHealthService.setHealthy(true);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        // Wait for initial health check
        Thread.sleep(1000);
        
        // Verify initial healthy state
        StepVerifier.create(healthMonitor.getCachedHealthStatus("failure-module-1"))
                .assertNext(status -> assertTrue(status.getIsHealthy()))
                .verifyComplete();
        
        // Set to unhealthy
        mockHealthService.setHealthy(false);
        
        // Wait for multiple health check cycles (3 failures should trigger unhealthy)
        Thread.sleep(12000); // Wait for at least 3 check cycles
        
        StepVerifier.create(healthMonitor.getCachedHealthStatus("failure-module-1"))
                .assertNext(status -> {
                    assertFalse(status.getIsHealthy());
                    assertEquals("failure-module-1", status.getServiceId());
                })
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(healthMonitor.stopMonitoring("failure-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle immediate health check")
    void testImmediateHealthCheck() {
        ModuleInfo moduleInfo = createModuleInfo("immediate-module-1", "localhost", grpcPort);
        mockHealthService.setHealthy(true);
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.GRPC, ""))
                .verifyComplete();
        
        // Perform immediate health check
        StepVerifier.create(healthMonitor.checkHealth("immediate-module-1"))
                .assertNext(status -> {
                    assertTrue(status.getIsHealthy());
                    assertEquals("immediate-module-1", status.getServiceId());
                })
                .verifyComplete();
        
        // Cleanup
        StepVerifier.create(healthMonitor.stopMonitoring("immediate-module-1"))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should return unknown status for non-existent module")
    void testNonExistentModule() {
        StepVerifier.create(healthMonitor.getCachedHealthStatus("non-existent-module"))
                .assertNext(status -> {
                    assertFalse(status.getIsHealthy());
                    assertEquals("non-existent-module", status.getServiceId());
                    assertTrue(status.getHealthDetails().contains("not found"));
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle HTTP health check with mock server")
    void testHttpHealthCheck() throws Exception {
        // Start a simple HTTP server
        try (MockHttpServer httpServer = new MockHttpServer()) {
            httpServer.start();
            
            ModuleInfo moduleInfo = createModuleInfo("http-module-1", "localhost", httpServer.getPort());
            httpServer.setHealthy(true);
            
            StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.HTTP, "/health"))
                    .verifyComplete();
            
            // Wait for health check
            Thread.sleep(1000);
            
            StepVerifier.create(healthMonitor.getCachedHealthStatus("http-module-1"))
                    .assertNext(status -> {
                        assertTrue(status.getIsHealthy());
                        assertEquals("http-module-1", status.getServiceId());
                        assertTrue(status.getHealthDetails().contains("HTTP 200"));
                    })
                    .verifyComplete();
            
            // Make it unhealthy
            httpServer.setHealthy(false);
            
            // Wait for health check
            Thread.sleep(11000);
            
            StepVerifier.create(healthMonitor.getCachedHealthStatus("http-module-1"))
                    .assertNext(status -> {
                        assertFalse(status.getIsHealthy());
                        assertEquals("http-module-1", status.getServiceId());
                    })
                    .verifyComplete();
            
            // Cleanup
            StepVerifier.create(healthMonitor.stopMonitoring("http-module-1"))
                    .verifyComplete();
        }
    }
    
    private ModuleInfo createModuleInfo(String serviceId, String host, int port) {
        return ModuleInfo.newBuilder()
                .setServiceId(serviceId)
                .setServiceName("test-service")
                .setHost(host)
                .setPort(port)
                .build();
    }
    
    /**
     * Mock gRPC health service for testing.
     */
    static class MockHealthService extends HealthGrpc.HealthImplBase {
        private volatile boolean healthy = true;
        private final AtomicInteger checkCount = new AtomicInteger(0);
        
        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
        
        void reset() {
            this.healthy = true;
            this.checkCount.set(0);
        }
        
        int getCheckCount() {
            return checkCount.get();
        }
        
        @Override
        public void check(io.grpc.health.v1.HealthCheckRequest request,
                         StreamObserver<HealthCheckResponse> responseObserver) {
            checkCount.incrementAndGet();
            
            HealthCheckResponse response = HealthCheckResponse.newBuilder()
                    .setStatus(healthy ? 
                            HealthCheckResponse.ServingStatus.SERVING : 
                            HealthCheckResponse.ServingStatus.NOT_SERVING)
                    .build();
            
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }
    }
    
    /**
     * Simple HTTP server for testing HTTP health checks.
     */
    static class MockHttpServer implements AutoCloseable {
        private com.sun.net.httpserver.HttpServer server;
        private volatile boolean healthy = true;
        
        void setHealthy(boolean healthy) {
            this.healthy = healthy;
        }
        
        void start() throws IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
            
            server.createContext("/health", exchange -> {
                int responseCode = healthy ? 200 : 503;
                String response = healthy ? "OK" : "Service Unavailable";
                
                exchange.sendResponseHeaders(responseCode, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            });
            
            server.start();
        }
        
        int getPort() {
            return server.getAddress().getPort();
        }
        
        @Override
        public void close() {
            if (server != null) {
                server.stop(0);
            }
        }
    }
}