package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.io.service.ServiceDefinition;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.testresources.core.TestResourcesResolver;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Debug test to understand test resources loading
 */
@MicronautTest(startApplication = false)
@Property(name = "micronaut.test-resources.enabled", value = "true")
public class TestResourcesDebugTest {
    
    private static final Logger logger = LoggerFactory.getLogger(TestResourcesDebugTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void debugTestResourcesProviders() {
        logger.info("=== Test Resources Debug Information ===");
        
        // Check if test resources are enabled
        Boolean testResourcesEnabled = applicationContext.getProperty("micronaut.test-resources.enabled", Boolean.class).orElse(false);
        logger.info("Test resources enabled: {}", testResourcesEnabled);
        
        // Check test resources server URI
        String serverUri = applicationContext.getProperty("micronaut.test-resources.server.uri", String.class).orElse("not set");
        logger.info("Test resources server URI: {}", serverUri);
        
        // List all available test resource resolvers
        logger.info("Available TestResourcesResolver implementations:");
        SoftServiceLoader<TestResourcesResolver> loader = SoftServiceLoader.load(TestResourcesResolver.class);
        List<ServiceDefinition<TestResourcesResolver>> definitions = new ArrayList<>();
        loader.iterator().forEachRemaining(definitions::add);
        
        if (definitions.isEmpty()) {
            logger.warn("No TestResourcesResolver implementations found!");
        } else {
            for (ServiceDefinition<TestResourcesResolver> definition : definitions) {
                logger.info(" - {}", definition.getName());
            }
        }
        
        // Check specific properties
        logger.info("Checking specific properties:");
        String[] propertiesToCheck = {
            "consul.client.host",
            "consul.client.port",
            "kafka.bootstrap.servers",
            "testcontainers.enabled",
            "testcontainers.consul"
        };
        
        for (String prop : propertiesToCheck) {
            String value = applicationContext.getProperty(prop, String.class).orElse("not set");
            logger.info(" - {}: {}", prop, value);
        }
        
        // Check if our consul test resource provider is in classpath
        try {
            Class<?> consulProvider = Class.forName("com.krickert.testcontainers.consul.ConsulTestResourceProvider");
            logger.info("ConsulTestResourceProvider class found: {}", consulProvider.getName());
        } catch (ClassNotFoundException e) {
            logger.error("ConsulTestResourceProvider class NOT found in classpath!");
        }
        
        assertThat(testResourcesEnabled).isTrue();
    }
}