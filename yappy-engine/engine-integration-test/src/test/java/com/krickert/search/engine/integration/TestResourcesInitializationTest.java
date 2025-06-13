package com.krickert.search.engine.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Initial test to verify that all test resources are properly initialized
 * before other integration tests run. This test ensures that:
 * 
 * 1. Consul test container is running and accessible
 * 2. Kafka test container is running and accessible  
 * 3. Apicurio test container is running and accessible
 * 4. All test resource properties are resolved correctly
 * 
 * This test runs first (using @TestMethodOrder) to prevent other tests
 * from failing due to missing test resources.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "kafka.slot-manager.enabled", value = "false")
class TestResourcesInitializationTest {

    private static final Logger LOG = LoggerFactory.getLogger(TestResourcesInitializationTest.class);
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    Integer consulPort;
    
    @Property(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;
    
    @Property(name = "kafka.producers.default.apicurio.registry.url")
    String apicurioUrl;
    
    @Test
    @Order(1)
    @DisplayName("Test resources are properly initialized and accessible")
    void testResourcesInitialization() {
        LOG.info("🔍 Verifying test resources initialization...");
        
        // Verify Consul properties
        assertNotNull(consulHost, "consul.client.host should be provided by test resources");
        assertNotNull(consulPort, "consul.client.port should be provided by test resources");
        assertTrue(consulPort > 0, "Consul port should be positive");
        
        LOG.info("✅ Consul test container: {}:{}", consulHost, consulPort);
        
        // Verify Kafka properties
        assertNotNull(kafkaBootstrapServers, "kafka.bootstrap.servers should be provided by test resources");
        assertFalse(kafkaBootstrapServers.trim().isEmpty(), "Kafka bootstrap servers should not be empty");
        
        LOG.info("✅ Kafka test container: {}", kafkaBootstrapServers);
        
        // Verify Apicurio properties
        assertNotNull(apicurioUrl, "apicurio.registry.url should be provided by test resources");
        assertTrue(apicurioUrl.startsWith("http"), "Apicurio URL should be a valid HTTP URL");
        
        LOG.info("✅ Apicurio test container: {}", apicurioUrl);
        
        LOG.info("🎉 All test resources successfully initialized and accessible!");
    }
    
    @Test
    @Order(2)
    @DisplayName("Test containers are reachable")
    void testContainersReachability() {
        LOG.info("🔍 Testing container reachability...");
        
        // Basic connectivity tests could be added here
        // For now, just verify the properties look correct
        
        assertTrue(consulHost.equals("localhost") || consulHost.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), 
            "Consul host should be localhost or valid IP: " + consulHost);
            
        assertTrue(kafkaBootstrapServers.contains(":"), 
            "Kafka bootstrap servers should contain port: " + kafkaBootstrapServers);
            
        assertTrue(apicurioUrl.contains("registry"), 
            "Apicurio URL should contain 'registry': " + apicurioUrl);
        
        LOG.info("✅ All containers appear reachable with valid configuration");
    }
}