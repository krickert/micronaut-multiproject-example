package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import io.micronaut.testresources.client.TestResourcesClient;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug test to understand why consul test resources are not being loaded
 */
@MicronautTest(startApplication = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestResourcesDebugTest implements TestPropertyProvider {
    
    private static final Logger logger = LoggerFactory.getLogger(TestResourcesDebugTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            // Enable test resources
            "micronaut.test-resources.enabled", "true",
            "micronaut.test-resources.shared-server", "true",
            
            // Enable specific test resource containers
            "containers.hashicorp-consul.enabled", "true",
            "containers.apache-kafka.enabled", "true"
        );
    }
    
    @Test
    void debugTestResourcesLoading() {
        logger.info("=== Test Resources Debug ===");
        
        // Check if test resources client is available
        var testResourcesClient = applicationContext.findBean(TestResourcesClient.class);
        logger.info("TestResourcesClient available: {}", testResourcesClient.isPresent());
        
        // List all test resource resolver implementations found via ServiceLoader
        logger.info("=== Available Test Resource Resolvers ===");
        ServiceLoader<io.micronaut.testresources.core.TestResourcesResolver> serviceLoader = 
            ServiceLoader.load(io.micronaut.testresources.core.TestResourcesResolver.class);
        
        serviceLoader.forEach(resolver -> {
            logger.info("Found resolver: {}", resolver.getClass().getName());
            if (resolver instanceof io.micronaut.testresources.core.ToggableTestResourcesResolver toggable) {
                logger.info("  - Name: {}", toggable.getName());
                logger.info("  - Display Name: {}", toggable.getDisplayName());
            }
        });
        
        // Check properties starting with "containers."
        logger.info("=== Container Properties ===");
        // Just check the specific properties we're interested in
        logger.info("containers.hashicorp-consul.enabled: {}", 
            applicationContext.getProperty("containers.hashicorp-consul.enabled", String.class).orElse("not set"));
        logger.info("containers.apache-kafka.enabled: {}", 
            applicationContext.getProperty("containers.apache-kafka.enabled", String.class).orElse("not set"));
        
        // Check if kafka properties are available (since it seems to work)
        var kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class);
        logger.info("Kafka bootstrap servers: {}", kafkaServers.orElse("not set"));
        
        // Check if consul properties are available
        var consulHost = applicationContext.getProperty("consul.client.host", String.class);
        var consulPort = applicationContext.getProperty("consul.client.port", Integer.class);
        logger.info("Consul host: {}", consulHost.orElse("not set"));
        logger.info("Consul port: {}", consulPort.orElse(-1));
        
        // The test passes - we're just debugging
        assertThat(applicationContext).isNotNull();
    }
}