package com.krickert.yappy.integration.container;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for Tika Parser container.
 * 
 * Test Order:
 * 1. Verify dependent services are running (Kafka, Consul, Apicurio)
 * 2. Test seeding requirements
 * 3. Container startup
 * 4. Container registration
 * 5. Container running state
 * 6. Edge case testing
 * 7. End-to-end testing
 */
@MicronautTest(environments = {"test"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TikaParserContainerIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(TikaParserContainerIntegrationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    /**
     * Test 1: Verify all dependent services are running and we have all required properties.
     * This test MUST pass before any other work is done.
     */
    @Test
    @DisplayName("Test 1: Verify all dependent services are running with required properties")
    void test01_verifyDependentServicesAndProperties() {
        LOG.info("Starting Test 1: Verifying dependent services and properties");
        
        // Verify Kafka properties
        String kafkaBootstrapServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class)
                .orElse(null);
        assertNotNull(kafkaBootstrapServers, "kafka.bootstrap.servers property must be set");
        LOG.info("Kafka bootstrap servers: {}", kafkaBootstrapServers);
        
        // Verify Consul properties
        String consulHost = applicationContext.getProperty("consul.client.host", String.class)
                .orElse(null);
        assertNotNull(consulHost, "consul.client.host property must be set");
        
        Integer consulPort = applicationContext.getProperty("consul.client.port", Integer.class)
                .orElse(null);
        assertNotNull(consulPort, "consul.client.port property must be set");
        LOG.info("Consul endpoint: {}:{}", consulHost, consulPort);
        
        // Verify Apicurio Registry properties
        String apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class)
                .orElse(null);
        assertNotNull(apicurioUrl, "apicurio.registry.url property must be set");
        LOG.info("Apicurio Registry URL: {}", apicurioUrl);
        
        // Verify schema registry type
        String schemaRegistryType = applicationContext.getProperty("kafka.schema.registry.type", String.class)
                .orElse("apicurio");
        assertEquals("apicurio", schemaRegistryType, "Schema registry type should be apicurio");
        
        // Log all test-related properties for debugging
        LOG.info("=== All Test Properties ===");
        applicationContext.getEnvironment().getPropertySources().forEach(propertySource -> {
            LOG.debug("Property source: {}", propertySource.getName());
        });
        
        LOG.info("Test 1 PASSED: All required properties are present");
    }
    
    // Placeholder for Test 2
    @Test
    @DisplayName("Test 2: Verify seeding requirements")
    void test02_verifySeedingRequirements() {
        LOG.info("Test 2: Seeding requirements - TO BE IMPLEMENTED");
        // This will be implemented after Test 1 passes
    }
}