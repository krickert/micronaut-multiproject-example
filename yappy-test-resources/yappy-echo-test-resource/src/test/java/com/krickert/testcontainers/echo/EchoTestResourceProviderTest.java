package com.krickert.testcontainers.echo;

import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test to verify the Echo test resource provider works correctly
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class EchoTestResourceProviderTest implements TestPropertyProvider {

    @Value("${echo.grpc.host}")
    String echoGrpcHost;

    @Value("${echo.grpc.port}")
    String echoGrpcPort;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "test.resources.scope", "echo"
        );
    }

    @Test
    @Disabled("Test resources need to be properly configured for this test")
    void testEchoTestResource() {
        assertNotNull(echoGrpcHost, "Echo gRPC host should be provided");
        assertNotNull(echoGrpcPort, "Echo gRPC port should be provided");
        
        System.out.println("Echo gRPC endpoint: " + echoGrpcHost + ":" + echoGrpcPort);
        
        // Basic validation
        assertTrue(Integer.parseInt(echoGrpcPort) > 0, "Port should be a positive number");
    }
}