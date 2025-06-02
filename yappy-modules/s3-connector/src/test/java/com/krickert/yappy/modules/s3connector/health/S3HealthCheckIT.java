package com.krickert.yappy.modules.s3connector.health;

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
 * Integration test for S3 health checks.
 * Verifies that health indicators work correctly with test containers (Moto S3 mock).
 */
@MicronautTest
class S3HealthCheckIT {
    
    @Inject
    S3HealthIndicator healthIndicator;
    
    @Test
    void testS3HealthIndicatorReturnsHealthy() {
        // When
        Publisher<HealthResult> result = healthIndicator.getResult();
        HealthResult healthResult = Mono.from(result).block();
        
        // Then
        assertNotNull(healthResult);
        assertEquals("s3", healthResult.getName());
        assertEquals(HealthStatus.UP, healthResult.getStatus());
        
        // Verify details are populated
        assertNotNull(healthResult.getDetails());
        Map<String, Object> details = (Map<String, Object>) healthResult.getDetails();
        assertTrue(details.containsKey("buckets_accessible"));
        assertTrue(details.containsKey("service_endpoint"));
        assertTrue(details.containsKey("region"));
        
        // S3 buckets accessible should be a number (could be 0 for Moto)
        Object bucketsAccessible = details.get("buckets_accessible");
        assertNotNull(bucketsAccessible);
        assertTrue(bucketsAccessible instanceof Integer, 
                "buckets_accessible should be an integer, got: " + bucketsAccessible.getClass());
        assertTrue((Integer) bucketsAccessible >= 0, 
                "buckets_accessible should be non-negative");
    }
    
    @Test
    void testS3HealthServiceExists() {
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
        Object region = details.get("region");
        assertNotNull(region, "region should be present");
        assertTrue(region instanceof String, "region should be a string");
        assertFalse(((String) region).isEmpty(), "region should not be empty");
        
        // Service endpoint should be present
        Object endpoint = details.get("service_endpoint");
        assertNotNull(endpoint, "service_endpoint should be present");
        assertTrue(endpoint instanceof String, "service_endpoint should be a string");
    }
}