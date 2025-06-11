package com.krickert.search.engine.core.integration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.client.TestResourcesClient;
import io.micronaut.testresources.client.TestResourcesClientFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal test to verify all containers start via test resources.
 * Uses @MicronautTest to ensure containers are started, but doesn't inject
 * properties that would cause initialization issues.
 */
@MicronautTest(propertySources = "application-minimal-test.yml")
public class MinimalContainerStartupTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(MinimalContainerStartupTest.class);
    
    @Inject
    ApplicationContext context;
    
    @Test
    void testAllContainersStart() {
        LOG.info("Starting container verification test...");
        
        // Extract the test resources client from the application context
        TestResourcesClient client = TestResourcesClientFactory.extractFrom(context);
        assertThat(client).isNotNull();
        
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
    }
}