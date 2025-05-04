package com.krickert.search.config.consul.testcontainers;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for TestContainers configuration.
 * This test verifies that TestContainers can be enabled or disabled through configuration.
 */
@MicronautTest(environments = "test-containers")
public class TestContainersConfigTest {
    private static final Logger LOG = LoggerFactory.getLogger(TestContainersConfigTest.class);

    @Inject
    ApplicationContext applicationContext;

    @Test
    void testContainersConfiguration() {
        // Get the configuration from the application context
        Map<String, Object> config = applicationContext.getEnvironment().getProperties("testcontainers");
        LOG.info("TestContainers configuration: {}", config);

        // Verify that the configuration is loaded correctly
        assertTrue(Boolean.parseBoolean(config.getOrDefault("enabled", "false").toString()),
                "TestContainers should be enabled globally");
        
        // Verify individual container configurations
        assertTrue(Boolean.parseBoolean(config.getOrDefault("kafka", "false").toString()),
                "Kafka container should be enabled");
        assertFalse(Boolean.parseBoolean(config.getOrDefault("moto", "true").toString()),
                "Moto container should be disabled");
        assertTrue(Boolean.parseBoolean(config.getOrDefault("consul", "false").toString()),
                "Consul container should be enabled");
        assertTrue(Boolean.parseBoolean(config.getOrDefault("apicurio", "false").toString()),
                "Apicurio container should be enabled");
    }

    @Test
    void testContainersManualConfiguration() {
        // Create a new application context with custom configuration
        Map<String, Object> properties = new HashMap<>();
        properties.put("testcontainers.enabled", "true");
        properties.put("testcontainers.kafka", "false");
        properties.put("testcontainers.moto", "true");
        properties.put("testcontainers.consul", "false");
        properties.put("testcontainers.apicurio.enabled", "true");

        try (ApplicationContext ctx = ApplicationContext.run(properties)) {
            // Get the configuration from the application context
            Map<String, Object> config = ctx.getEnvironment().getProperties("testcontainers");
            LOG.info("Manual TestContainers configuration: {}", config);

            // Verify that the configuration is loaded correctly
            assertTrue(Boolean.parseBoolean(config.getOrDefault("enabled", "false").toString()),
                    "TestContainers should be enabled globally");
            
            // Verify individual container configurations
            assertFalse(Boolean.parseBoolean(config.getOrDefault("kafka", "true").toString()),
                    "Kafka container should be disabled");
            assertTrue(Boolean.parseBoolean(config.getOrDefault("moto", "false").toString()),
                    "Moto container should be enabled");
            assertFalse(Boolean.parseBoolean(config.getOrDefault("consul", "true").toString()),
                    "Consul container should be disabled");
            
            // For nested properties, we need to check differently
            Map<String, Object> apicurioConfig = ctx.getEnvironment().getProperties("testcontainers.apicurio");
            assertTrue(Boolean.parseBoolean(apicurioConfig.getOrDefault("enabled", "false").toString()),
                    "Apicurio container should be enabled");
        }
    }
}