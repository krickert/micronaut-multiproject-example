package com.krickert.search.engine.health;

import com.krickert.search.engine.health.impl.ModuleHealthMonitorImpl;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.yappy.registration.api.HealthCheckType;
import io.micronaut.scheduling.TaskScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ModuleHealthMonitor without requiring test containers.
 */
class ModuleHealthMonitorUnitTest {
    
    @Mock
    private TaskScheduler taskScheduler;
    
    @Mock
    private ScheduledFuture<?> scheduledFuture;
    
    private ModuleHealthMonitorImpl healthMonitor;
    
    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        
        // Mock the scheduler to not actually schedule tasks
        when(taskScheduler.scheduleAtFixedRate(any(Duration.class), any(Duration.class), any(Runnable.class)))
                .thenReturn((ScheduledFuture) scheduledFuture);
        
        healthMonitor = new ModuleHealthMonitorImpl(taskScheduler);
    }
    
    @Test
    @DisplayName("Should start monitoring a module")
    void testStartMonitoring() {
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceId("test-module-1")
                .setServiceName("Test Service")
                .setHost("localhost")
                .setPort(8080)
                .build();
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.HTTP, "/health"))
                .verifyComplete();
        
        // Verify the module is being monitored
        StepVerifier.create(healthMonitor.getCachedHealthStatus("test-module-1"))
                .assertNext(status -> {
                    assertEquals("test-module-1", status.getServiceId());
                    assertEquals("Test Service", status.getServiceName());
                    assertTrue(status.getIsHealthy()); // Initial status is healthy
                    assertTrue(status.getHealthDetails().contains("Initial status"));
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should stop monitoring a module")
    void testStopMonitoring() {
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceId("test-module-2")
                .setServiceName("Test Service 2")
                .setHost("localhost")
                .setPort(8081)
                .build();
        
        // Start monitoring
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.TCP, ""))
                .verifyComplete();
        
        // Stop monitoring
        StepVerifier.create(healthMonitor.stopMonitoring("test-module-2"))
                .verifyComplete();
        
        // Verify the module is no longer monitored
        StepVerifier.create(healthMonitor.getCachedHealthStatus("test-module-2"))
                .assertNext(status -> {
                    assertFalse(status.getIsHealthy());
                    assertTrue(status.getHealthDetails().contains("not found"));
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should add and remove health change listeners")
    void testHealthChangeListeners() {
        ModuleHealthMonitor.HealthChangeListener listener = mock(ModuleHealthMonitor.HealthChangeListener.class);
        
        healthMonitor.addHealthChangeListener(listener);
        healthMonitor.removeHealthChangeListener(listener);
        
        // No exceptions should be thrown
        assertTrue(true);
    }
    
    @Test
    @DisplayName("Should return unknown status for non-existent module")
    void testNonExistentModule() {
        StepVerifier.create(healthMonitor.getCachedHealthStatus("non-existent"))
                .assertNext(status -> {
                    assertEquals("non-existent", status.getServiceId());
                    assertFalse(status.getIsHealthy());
                    assertTrue(status.getHealthDetails().contains("not found"));
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle multiple modules")
    void testMultipleModules() {
        // Add multiple modules
        for (int i = 1; i <= 3; i++) {
            ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                    .setServiceId("module-" + i)
                    .setServiceName("Service " + i)
                    .setHost("localhost")
                    .setPort(8080 + i)
                    .build();
            
            StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.TCP, ""))
                    .verifyComplete();
        }
        
        // Verify all modules are monitored
        for (int i = 1; i <= 3; i++) {
            String moduleId = "module-" + i;
            StepVerifier.create(healthMonitor.getCachedHealthStatus(moduleId))
                    .assertNext(status -> {
                        assertEquals(moduleId, status.getServiceId());
                        assertEquals("Service " + moduleId.substring(7), status.getServiceName());
                        assertTrue(status.getIsHealthy());
                    })
                    .verifyComplete();
        }
    }
    
    @Test
    @DisplayName("Should handle TTL health check type")
    void testTtlHealthCheck() {
        ModuleInfo moduleInfo = ModuleInfo.newBuilder()
                .setServiceId("ttl-module")
                .setServiceName("TTL Service")
                .setHost("localhost")
                .setPort(8090)
                .build();
        
        StepVerifier.create(healthMonitor.startMonitoring(moduleInfo, HealthCheckType.TTL, ""))
                .verifyComplete();
        
        // TTL modules should start as healthy
        StepVerifier.create(healthMonitor.getCachedHealthStatus("ttl-module"))
                .assertNext(status -> {
                    assertEquals("ttl-module", status.getServiceId());
                    assertTrue(status.getIsHealthy());
                })
                .verifyComplete();
    }
}