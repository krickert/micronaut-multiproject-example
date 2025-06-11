package com.krickert.search.engine.core.integration;

import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple test to verify all containers start via test resources.
 * Uses the test resources client directly without Micronaut context.
 */
public class SimpleContainerStartupTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(SimpleContainerStartupTest.class);
    
    @Test
    void testAllContainersStart() {
        // Create test resources client
        TestResourcesClient client = TestResourcesClientFactory.fromSystemProperties()
            .orElseGet(() -> TestResourcesClientFactory.findByConvention()
                .orElseThrow(() -> new RuntimeException("Could not find test resources client configuration")));
        
        LOG.info("Starting container verification test...");
        
        // Base infrastructure properties to check
        List<String> baseProperties = Arrays.asList(
            "consul.client.host",
            "kafka.bootstrap.servers",
            "apicurio.registry.url",
            "opensearch.url",
            "aws.endpoint"
        );
        
        // Module properties to check
        List<String> moduleProperties = Arrays.asList(
            "chunker.grpc.host",
            "tika.grpc.host",
            "embedder.grpc.host",
            "echo.grpc.host",
            "test-module.grpc.host"
        );
        
        LOG.info("\n=== Verifying Base Infrastructure Containers ===");
        for (String property : baseProperties) {
            Optional<String> value = client.resolve(property, Map.of(), Map.of());
            assertThat(value).as("Property %s should be resolved", property).isPresent();
            LOG.info("✓ {} = {}", property, value.get());
        }
        
        LOG.info("\n=== Verifying Module Containers ===");
        for (String property : moduleProperties) {
            Optional<String> value = client.resolve(property, Map.of(), Map.of());
            assertThat(value).as("Property %s should be resolved", property).isPresent();
            LOG.info("✓ {} = {}", property, value.get());
        }
        
        LOG.info("\n=== All containers started successfully! ===");
        
        // No need to close the client - it doesn't have a close method
        // The containers will continue running for other tests
    }
}