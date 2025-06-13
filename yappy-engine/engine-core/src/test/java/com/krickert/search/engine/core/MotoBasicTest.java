package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic test to verify Moto test resource provider is working.
 * This test has minimal requirements to help debug the issue.
 */
@MicronautTest
public class MotoBasicTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(MotoBasicTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Override
    public Map<String, String> getProperties() {
        // Tell Micronaut we want to use Glue for schema registry
        return Map.of(
            "kafka.schema.registry.type", "glue",
            "testcontainers.enabled", "true",
            "testcontainers.moto", "true"
        );
    }
    
    @Test
    void testMotoPropertiesAvailable() {
        LOG.info("=== Testing Moto Test Resource Provider ===");
        
        // Check various property names that Moto might provide
        String[] propertiesToCheck = {
            "aws.endpoint",
            "aws.glue.endpoint", 
            "aws.region",
            "aws.access-key-id",
            "aws.secret-access-key",
            "glue.registry.url",
            "aws.service-endpoint",
            "software.amazon.awssdk.glue.endpoint"
        };
        
        boolean foundAnyMotoProperty = false;
        
        for (String prop : propertiesToCheck) {
            String value = applicationContext.getProperty(prop, String.class).orElse(null);
            if (value != null) {
                LOG.info("✅ Found property: {} = {}", prop, value);
                foundAnyMotoProperty = true;
            } else {
                LOG.info("❌ Not found: {}", prop);
            }
        }
        
        // Also check if test resources are enabled
        Boolean testResourcesEnabled = applicationContext.getProperty("micronaut.test-resources.enabled", Boolean.class).orElse(false);
        LOG.info("Test resources enabled: {}", testResourcesEnabled);
        
        // Check if the shared server is being used
        Boolean sharedServer = applicationContext.getProperty("micronaut.test-resources.shared-server", Boolean.class).orElse(false);
        LOG.info("Using shared test resources server: {}", sharedServer);
        
        // Let's also check if Kafka and Consul are working (as a baseline)
        String kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse(null);
        
        LOG.info("Kafka bootstrap servers: {}", kafkaServers);
        LOG.info("Consul host: {}", consulHost);
        
        // Assert that at least one Moto property was found
        assertThat(foundAnyMotoProperty)
            .as("Moto test resource provider should provide at least one AWS/Glue property")
            .isTrue();
    }
}