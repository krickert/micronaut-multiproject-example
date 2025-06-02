package com.krickert.yappy.modules.opensearchsink.health;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for OpenSearch health checks.
 * Verifies that health indicators work correctly with test containers.
 */
@MicronautTest
class OpenSearchHealthCheckIT {
    
    @Inject
    OpenSearchHealthIndicator healthIndicator;
    
    @Test
    void testOpenSearchHealthIndicatorReturnsHealthy() {
        // When
        Publisher<HealthResult> result = healthIndicator.getResult();
        HealthResult healthResult = Mono.from(result).block();
        
        // Then
        assertNotNull(healthResult);
        assertEquals("opensearch", healthResult.getName());
        assertEquals(HealthStatus.UP, healthResult.getStatus());
        
        // Verify details are populated
        assertNotNull(healthResult.getDetails());
        Map<String, Object> details = (Map<String, Object>) healthResult.getDetails();
        assertTrue(details.containsKey("cluster_name"));
        assertTrue(details.containsKey("status"));
        assertTrue(details.containsKey("number_of_nodes"));
        assertTrue(details.containsKey("active_shards"));
        
        // OpenSearch status can be any valid cluster health status  
        Object clusterStatusObj = details.get("status");
        assertNotNull(clusterStatusObj, "Cluster status should not be null");
        String clusterStatus = clusterStatusObj.toString();
        assertNotNull(clusterStatus, "Cluster status string should not be null");
        assertFalse(clusterStatus.isEmpty(), "Cluster status should not be empty");
    }
    
    @Test
    void testOpenSearchHealthServiceExists() {
        // The health indicator should always be available
        assertNotNull(healthIndicator, "HealthIndicator should be available");
    }
    
    @Test
    void testHealthIndicatorDetails() {
        // When
        Publisher<HealthResult> result = healthIndicator.getResult();
        HealthResult healthResult = Mono.from(result).block();
        
        // Then
        assertNotNull(healthResult);
        
        // Verify specific health details
        Map<String, Object> details = (Map<String, Object>) healthResult.getDetails();
        Object numberOfNodes = details.get("number_of_nodes");
        assertNotNull(numberOfNodes, "number_of_nodes should be present");
        assertTrue(numberOfNodes instanceof Integer, "number_of_nodes should be an integer");
        assertTrue((Integer) numberOfNodes > 0, "Should have at least one node");
        
        Object activeShards = details.get("active_shards");
        assertNotNull(activeShards, "active_shards should be present");
        assertTrue(activeShards instanceof Integer, "active_shards should be an integer");
        assertTrue((Integer) activeShards >= 0, "active_shards should be non-negative");
    }
}