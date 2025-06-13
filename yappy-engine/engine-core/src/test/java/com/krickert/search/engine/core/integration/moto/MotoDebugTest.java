package com.krickert.search.engine.core.integration.moto;

import io.micronaut.context.ApplicationContext;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple debug test to verify Moto properties are available
 */
@MicronautTest(environments = "test-glue")
public class MotoDebugTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(MotoDebugTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void debugMotoProperties() {
        LOG.info("=== Moto Properties Debug ===");
        
        // Properties that Moto should provide
        String glueRegistryUrl = applicationContext.getProperty("glue.registry.url", String.class).orElse(null);
        String awsGlueEndpoint = applicationContext.getProperty("aws.glue.endpoint", String.class).orElse(null);
        String awsEndpoint = applicationContext.getProperty("aws.endpoint", String.class).orElse(null);
        String awsRegion = applicationContext.getProperty("aws.region", String.class).orElse(null);
        String awsAccessKey = applicationContext.getProperty("aws.access-key-id", String.class).orElse(null);
        
        LOG.info("glue.registry.url = {}", glueRegistryUrl);
        LOG.info("aws.glue.endpoint = {}", awsGlueEndpoint);
        LOG.info("aws.endpoint = {}", awsEndpoint);
        LOG.info("aws.region = {}", awsRegion);
        LOG.info("aws.access-key-id = {}", awsAccessKey != null ? "***" : null);
        
        // Check kafka and consul are working
        String kafkaServers = applicationContext.getProperty("kafka.bootstrap.servers", String.class).orElse(null);
        String consulHost = applicationContext.getProperty("consul.client.host", String.class).orElse(null);
        
        LOG.info("kafka.bootstrap.servers = {}", kafkaServers);
        LOG.info("consul.client.host = {}", consulHost);
        
        // Assert at least one Moto property is available
        boolean motoAvailable = glueRegistryUrl != null || awsGlueEndpoint != null || awsEndpoint != null;
        
        if (!motoAvailable) {
            LOG.error("No Moto properties found! The Moto test resource provider may not be running.");
            LOG.info("Checking test resources configuration...");
            Boolean testResourcesEnabled = applicationContext.getProperty("micronaut.test-resources.enabled", Boolean.class).orElse(false);
            LOG.info("Test resources enabled: {}", testResourcesEnabled);
        }
        
        assertThat(motoAvailable)
            .as("At least one Moto property should be available")
            .isTrue();
    }
}