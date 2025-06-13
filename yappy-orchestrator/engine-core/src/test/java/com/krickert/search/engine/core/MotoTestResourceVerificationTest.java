package com.krickert.search.engine.core;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import io.micronaut.context.ApplicationContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Simple test to verify Moto test resource provider is working
 */
@MicronautTest
public class MotoTestResourceVerificationTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(MotoTestResourceVerificationTest.class);
    
    @Inject
    ApplicationContext applicationContext;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "kafka.schema.registry.type", "glue"
        );
    }
    
    @Test
    void testMotoEndpointProvided() {
        // Try to get the property from the application context
        String glueEndpoint = applicationContext.getProperty("aws.glue.endpoint", String.class).orElse(null);
        
        LOG.info("Checking for aws.glue.endpoint property...");
        LOG.info("aws.glue.endpoint = {}", glueEndpoint);
        
        // Also check other AWS properties
        String awsRegion = applicationContext.getProperty("aws.region", String.class).orElse(null);
        LOG.info("aws.region = {}", awsRegion);
        
        String awsAccessKey = applicationContext.getProperty("aws.access-key-id", String.class).orElse(null);
        LOG.info("aws.access-key-id = {}", awsAccessKey);
        
        // Check if property exists
        assertThat(glueEndpoint)
            .as("Moto test resource should provide aws.glue.endpoint")
            .isNotNull()
            .isNotEmpty()
            .startsWith("http");
    }
}