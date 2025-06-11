package com.krickert.testcontainers.embedder;

import io.micronaut.context.annotation.Value;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic test to verify that the Embedder test resource provider is working correctly.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmbedderTestResourceProviderTest implements TestPropertyProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(EmbedderTestResourceProviderTest.class);
    
    @Value("${embedder.grpc.host}")
    String embedderHost;
    
    @Value("${embedder.grpc.port}")
    String embedderPort;
    
    @Value("${embedder.internal.host}")
    String embedderInternalHost;
    
    @Value("${embedder.container.id:unknown}")
    String containerId;
    
    @Override
    public Map<String, String> getProperties() {
        return Map.of(
            "test.resources.scope", "embedder-test"
        );
    }
    
    @Test
    @Disabled("Test resources need to be properly configured for this test")
    void testEmbedderTestResourceProvider() {
        LOG.info("Embedder external host: {}", embedderHost);
        LOG.info("Embedder external port: {}", embedderPort);
        LOG.info("Embedder internal host: {}", embedderInternalHost);
        LOG.info("Embedder container ID: {}", containerId);
        
        assertNotNull(embedderHost, "Embedder host should be set");
        assertNotNull(embedderPort, "Embedder port should be set");
        assertNotNull(embedderInternalHost, "Embedder internal host should be set");
        
        // Basic validation
        assertTrue(Integer.parseInt(embedderPort) > 0, "Port should be a positive number");
        assertFalse(containerId.equals("unknown"), "Container ID should be set");
    }
}