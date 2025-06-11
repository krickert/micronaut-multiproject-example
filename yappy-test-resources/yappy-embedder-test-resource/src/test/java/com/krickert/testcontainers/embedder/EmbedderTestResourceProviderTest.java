package com.krickert.testcontainers.embedder;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.extensions.junit5.annotation.TestResourcesScope;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Basic test to verify that the Embedder test resource provider is working correctly.
 */
@MicronautTest
@TestResourcesScope("embedder-test")
class EmbedderTestResourceProviderTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EmbedderTestResourceProviderTest.class);
    
    @org.junit.jupiter.api.Value("${embedder.grpc.host}")
    String embedderHost;
    
    @org.junit.jupiter.api.Value("${embedder.grpc.port}")
    String embedderPort;
    
    @org.junit.jupiter.api.Value("${embedder.internal.host}")
    String embedderInternalHost;
    
    @org.junit.jupiter.api.Value("${embedder.container.id:unknown}")
    String containerId;
    
    @Test
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
        assertTrue(!containerId.equals("unknown"), "Container ID should be set");
    }
}