package com.krickert.testcontainers.tika;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that verifies the Tika test resource provider is working correctly.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TikaTestResourceProviderTest implements TestPropertyProvider {

    @Inject
    @jakarta.inject.Named("tika.grpc.host")
    String tikaHost;

    @Inject
    @jakarta.inject.Named("tika.grpc.port")
    String tikaPort;

    @Test
    void testTikaTestResourceProvider() {
        assertNotNull(tikaHost, "Tika host should be provided");
        assertNotNull(tikaPort, "Tika port should be provided");
        
        // Verify the port is a valid number
        int port = Integer.parseInt(tikaPort);
        assertTrue(port > 0 && port < 65536, "Port should be valid");
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(
                "test.resources.enabled", "true"
        );
    }
}