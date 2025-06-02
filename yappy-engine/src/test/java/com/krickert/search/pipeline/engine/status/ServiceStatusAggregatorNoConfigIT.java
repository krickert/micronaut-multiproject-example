package com.krickert.search.pipeline.engine.status;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.pipeline.status.ServiceStatusAggregator;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ServiceStatusAggregator when no cluster configuration exists.
 * This is a valid state that should be handled gracefully.
 */
@MicronautTest(environments = "test")
@Property(name = "app.config.cluster-name", value = "non-existent-cluster-for-testing")
class ServiceStatusAggregatorNoConfigIT {

    @Inject
    ServiceStatusAggregator serviceStatusAggregator;

    @Inject
    DynamicConfigurationManager dynamicConfigManager;

    @Inject
    ConsulKvService consulKvService;

    private static final String TEST_SERVICE_NAME = "test-service";
    private static final String STATUS_KV_PREFIX = "yappy/status/services/";

    @BeforeEach
    void setUp() {
        // Clean up any existing test data in Consul
        consulKvService.deleteKey(STATUS_KV_PREFIX + TEST_SERVICE_NAME).block();
    }

    @Test
    void testAggregateAndStore_NoClusterConfig() {
        // Given: No cluster config is available
        // Verify the dynamic config manager has no config loaded
        assertFalse(dynamicConfigManager.getCurrentPipelineClusterConfig().isPresent(),
                "DynamicConfigurationManager should NOT have any cluster configuration");
        
        // When: aggregateAndStoreServiceStatuses is called
        // It should handle this gracefully without throwing exceptions
        assertDoesNotThrow(() -> serviceStatusAggregator.aggregateAndStoreServiceStatuses());
        
        // Then: No status should be written to KV for any service
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
        )
        .expectNextCount(0)
        .expectComplete()
        .verify(Duration.ofSeconds(5));
    }
    
    @Test
    void testUpdateServiceStatusToProxying_NoClusterConfig() {
        // Given: No cluster config is available
        assertFalse(dynamicConfigManager.getCurrentPipelineClusterConfig().isPresent(),
                "DynamicConfigurationManager should NOT have any cluster configuration");
        
        // When: updateServiceStatusToProxying is called
        // It should still write a status even without cluster config
        serviceStatusAggregator.updateServiceStatusToProxying(TEST_SERVICE_NAME);
        
        // Wait for async processing
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Then: Status should still be written as this method doesn't depend on cluster config
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + TEST_SERVICE_NAME)
                        .filter(Optional::isPresent)
                        .map(Optional::get)
        )
        .assertNext(kvValue -> {
            assertNotNull(kvValue);
            assertTrue(kvValue.contains("ACTIVE_PROXYING"));
            assertTrue(kvValue.contains(TEST_SERVICE_NAME));
        })
        .expectComplete()
        .verify(Duration.ofSeconds(5));
    }
    
    @Test
    void testScheduledAggregation_NoClusterConfig() {
        // Given: No cluster config is available
        assertFalse(dynamicConfigManager.getCurrentPipelineClusterConfig().isPresent(),
                "DynamicConfigurationManager should NOT have any cluster configuration");
        
        // The scheduled aggregation should run without errors
        // Wait for at least one scheduled run (initial delay is 10s, but we'll trigger manually)
        serviceStatusAggregator.aggregateAndStoreServiceStatuses();
        
        // Wait a bit to ensure no unexpected errors occur
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Verify no exceptions were thrown and no statuses were written
        StepVerifier.create(
                consulKvService.getValue(STATUS_KV_PREFIX + "any-service")
                        .filter(Optional::isPresent)
        )
        .expectNextCount(0)
        .expectComplete()
        .verify(Duration.ofSeconds(2));
    }
}