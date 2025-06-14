package com.krickert.search.engine.integration;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Debug test to understand why engine test resource isn't starting
 */
@MicronautTest
public class EngineTestResourceDebugTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineTestResourceDebugTest.class);
    
    @Inject
    ApplicationContext context;
    
    // Try different ways to access the property
    @Property(name = "engine.grpc.host", defaultValue = "NOT_SET")
    String engineHostProperty;
    
    @Property(name = "engine.grpc.port", defaultValue = "0")
    String enginePortProperty;
    
    @Value("${engine.grpc.host:NOT_SET}")
    String engineHostValue;
    
    @Value("${engine.grpc.port:0}")
    String enginePortValue;
    
    @Test
    void debugEngineTestResource() {
        LOG.info("=== Engine Test Resource Debug ===");
        
        // Check @Property injection
        LOG.info("@Property injection:");
        LOG.info("  engine.grpc.host = {}", engineHostProperty);
        LOG.info("  engine.grpc.port = {}", enginePortProperty);
        
        // Check @Value injection
        LOG.info("@Value injection:");
        LOG.info("  engine.grpc.host = {}", engineHostValue);
        LOG.info("  engine.grpc.port = {}", enginePortValue);
        
        // Check ApplicationContext
        LOG.info("ApplicationContext lookup:");
        var hostOpt = context.getProperty("engine.grpc.host", String.class);
        var portOpt = context.getProperty("engine.grpc.port", String.class);
        LOG.info("  engine.grpc.host = {}", hostOpt.orElse("NOT_FOUND"));
        LOG.info("  engine.grpc.port = {}", portOpt.orElse("NOT_FOUND"));
        
        // Check if grpc client config exists
        LOG.info("gRPC client config:");
        var grpcAddress = context.getProperty("grpc.client.engine.address", String.class);
        LOG.info("  grpc.client.engine.address = {}", grpcAddress.orElse("NOT_FOUND"));
        
        // List all properties containing "engine"
        LOG.info("All properties containing 'engine':");
        context.getEnvironment().getPropertySources().forEach(ps -> {
            if (ps.getName().contains("application")) {
                // PropertySource doesn't directly support forEach, need to iterate differently
                LOG.info("  Found property source: {}", ps.getName());
            }
        });
        
        // Check test resources status
        var testResourcesEnabled = context.getProperty("micronaut.test-resources.enabled", Boolean.class).orElse(false);
        var testResourcesServer = context.getProperty("micronaut.test-resources.server.uri", String.class);
        LOG.info("Test resources:");
        LOG.info("  enabled = {}", testResourcesEnabled);
        LOG.info("  server = {}", testResourcesServer.orElse("NOT_SET"));
        
        // The test passes if we at least have the configuration attempting to use engine properties
        assertTrue(grpcAddress.isPresent() || !engineHostProperty.equals("NOT_SET"), 
            "Engine test resource should be triggered by grpc.client.engine.address configuration");
    }
}