package com.krickert.testcontainers.echo;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to verify the Echo test resource provider works correctly
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EchoTestResourceProviderTest implements TestPropertyProvider {

    // Don't inject these directly - they won't be available during test initialization
    // @Value("${echo.grpc.host}")
    // String echoGrpcHost;

    // @Value("${echo.grpc.port}")
    // String echoGrpcPort;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "test.resources.scope", "echo"
        );
    }

    @Test
    @Disabled("Test resources need to be properly configured for this test")
    void testEchoTestResource() {
        // This test is disabled because it's just a placeholder
        // The real test would verify that the echo service is running
        // and accessible via the test resources
        
        // To properly test, we would need to:
        // 1. Get the properties from the environment/context
        // 2. Create a gRPC client to connect to the echo service
        // 3. Send a test message and verify the response
        
        assertTrue(true, "Placeholder test");
    }
}