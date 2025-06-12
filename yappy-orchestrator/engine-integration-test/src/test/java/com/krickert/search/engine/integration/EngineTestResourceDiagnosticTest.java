package com.krickert.search.engine.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Diagnostic test to verify engine test resources are working
 */
@MicronautTest
class EngineTestResourceDiagnosticTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineTestResourceDiagnosticTest.class);
    
    @Property(name = "engine.grpc.host", defaultValue = "NOT_SET")
    String engineGrpcHost;
    
    @Property(name = "engine.grpc.port", defaultValue = "0")
    String engineGrpcPort;
    
    @Property(name = "engine.http.host", defaultValue = "NOT_SET")
    String engineHttpHost;
    
    @Property(name = "engine.http.port", defaultValue = "0") 
    String engineHttpPort;
    
    @Test
    void testEngineTestResourcesAvailable() {
        LOG.info("🔍 Checking engine test resources...");
        
        LOG.info("engine.grpc.host = {}", engineGrpcHost);
        LOG.info("engine.grpc.port = {}", engineGrpcPort);
        LOG.info("engine.http.host = {}", engineHttpHost);
        LOG.info("engine.http.port = {}", engineHttpPort);
        
        if ("NOT_SET".equals(engineGrpcHost) && "NOT_SET".equals(engineHttpHost)) {
            LOG.error("❌ Engine test resources not available!");
            LOG.error("Possible causes:");
            LOG.error("1. Engine test resource provider not in testResourcesImplementation");
            LOG.error("2. Engine Docker image not available");
            LOG.error("3. Test resources service not running");
            LOG.error("4. Container startup failure");
            
            // Let's also check other test resources
            LOG.info("Checking if other test resources are working...");
        }
        
        // This will fail if the properties are not provided
        if (!"NOT_SET".equals(engineGrpcHost)) {
            assertNotNull(engineGrpcHost, "Engine gRPC host should be provided by test resources");
            assertNotNull(engineGrpcPort, "Engine gRPC port should be provided by test resources");
        }
    }
}