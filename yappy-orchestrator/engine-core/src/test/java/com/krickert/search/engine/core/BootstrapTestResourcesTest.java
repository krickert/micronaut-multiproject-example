package com.krickert.search.engine.core;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap test to ensure all test resources are started
 * Run this test first to initialize all containers
 */
@MicronautTest(startApplication = false)
@Property(name = "micronaut.test-resources.enabled", value = "true")
public class BootstrapTestResourcesTest {
    
    private static final Logger logger = LoggerFactory.getLogger(BootstrapTestResourcesTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void bootstrapAllTestResources() {
        logger.info("=== Bootstrapping All Test Resources ===");
        
        // Request all infrastructure properties to trigger container startup
        String[] infrastructureProperties = {
            "consul.client.host",
            "consul.client.port",
            "kafka.bootstrap.servers",
            "apicurio.registry.url",
            "opensearch.url",
            "aws.endpoint"
        };
        
        logger.info("Requesting infrastructure properties to trigger container startup:");
        for (String prop : infrastructureProperties) {
            String value = applicationContext.getProperty(prop, String.class).orElse("not set");
            logger.info(" - {}: {}", prop, value);
        }
        
        // Also request module properties
        String[] moduleProperties = {
            "echo.grpc.host",
            "echo.grpc.port",
            "chunker.grpc.host",
            "chunker.grpc.port",
            "tika.grpc.host",
            "tika.grpc.port",
            "embedder.grpc.host",
            "embedder.grpc.port",
            "test-module.grpc.host",
            "test-module.grpc.port"
        };
        
        logger.info("Requesting module properties to trigger container startup:");
        for (String prop : moduleProperties) {
            String value = applicationContext.getProperty(prop, String.class).orElse("not set");
            logger.info(" - {}: {}", prop, value);
        }
        
        // Verify at least the critical ones are set
        assertThat(applicationContext.getProperty("kafka.bootstrap.servers", String.class))
            .isPresent()
            .get()
            .asString()
            .isNotBlank();
            
        logger.info("=== Test Resources Bootstrap Complete ===");
    }
}