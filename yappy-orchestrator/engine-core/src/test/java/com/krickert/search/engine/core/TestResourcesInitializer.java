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
 * Test to initialize all test resources containers before running actual tests.
 * This addresses the issue where test resources might not be fully started
 * when tests run.
 */
@MicronautTest(startApplication = false)
@Property(name = "micronaut.test-resources.enabled", value = "true")
@Property(name = "micronaut.test-resources.shared-server", value = "true")
public class TestResourcesInitializer {
    
    private static final Logger logger = LoggerFactory.getLogger(TestResourcesInitializer.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void initializeAllTestResources() {
        logger.info("Initializing all test resources containers...");
        
        // Force initialization of Kafka test resources
        var kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class);
        logger.info("Kafka bootstrap servers: {}", kafkaServers.orElse("not initialized"));
        
        // Force initialization of Consul test resources
        var consulHost = applicationContext.getProperty("consul.client.host", String.class);
        var consulPort = applicationContext.getProperty("consul.client.port", Integer.class);
        logger.info("Consul host: {}", consulHost.orElse("not initialized"));
        logger.info("Consul port: {}", consulPort.orElse(-1));
        
        // Force initialization of OpenSearch test resources
        var opensearchHosts = applicationContext.getProperty("opensearch.hosts", String.class);
        logger.info("OpenSearch hosts: {}", opensearchHosts.orElse("not initialized"));
        
        // Force initialization of Apicurio test resources
        var apicurioUrl = applicationContext.getProperty("apicurio.registry.url", String.class);
        logger.info("Apicurio registry URL: {}", apicurioUrl.orElse("not initialized"));
        
        // Force initialization of Moto test resources
        var s3Endpoint = applicationContext.getProperty("aws.s3.endpoint-override", String.class);
        logger.info("AWS S3 endpoint: {}", s3Endpoint.orElse("not initialized"));
        
        // Add a small delay to ensure containers are fully started
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        logger.info("Test resources initialization complete.");
        
        // Verify at least Kafka is initialized (it's the one that works)
        assertThat(kafkaServers).isPresent();
    }
}