package com.krickert.search.engine.core.integration;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test just the chunker container startup
 */
@MicronautTest(startApplication = false)
public class ChunkerOnlyTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(ChunkerOnlyTest.class);
    
    @Property(name = "chunker.grpc.host")
    String chunkerHost;
    
    @Property(name = "chunker.grpc.port")
    String chunkerPort;
    
    @Test
    void testChunkerStarts() {
        LOG.info("Testing chunker container startup");
        assertNotNull(chunkerHost, "Chunker host should be available");
        assertNotNull(chunkerPort, "Chunker port should be available");
        LOG.info("✓ Chunker available at {}:{}", chunkerHost, chunkerPort);
    }
}