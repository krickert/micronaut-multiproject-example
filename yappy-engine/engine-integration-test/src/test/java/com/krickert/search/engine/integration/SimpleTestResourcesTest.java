package com.krickert.search.engine.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to verify that test resources are working without loading
 * complex application components that might have additional dependencies.
 */
@MicronautTest(startApplication = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleTestResourcesTest {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleTestResourcesTest.class);
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    Integer consulPort;
    
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Test
    @DisplayName("Test resources provide required properties")
    void testResourcesProvideProperties() {
        LOG.info("🔍 Verifying test resources provide required properties...");
        
        // Verify Consul properties
        assertNotNull(consulHost, "consul.client.host should be provided by test resources");
        assertNotNull(consulPort, "consul.client.port should be provided by test resources");
        assertTrue(consulPort > 0, "Consul port should be positive");
        
        LOG.info("✅ Consul test container: {}:{}", consulHost, consulPort);
        
        // Verify Kafka properties
        assertNotNull(kafkaBootstrapServers, "kafka.bootstrap.servers should be provided by test resources");
        assertFalse(kafkaBootstrapServers.trim().isEmpty(), "Kafka bootstrap servers should not be empty");
        
        LOG.info("✅ Kafka test container: {}", kafkaBootstrapServers);
        
        LOG.info("🎉 Test resources are working correctly!");
    }
}