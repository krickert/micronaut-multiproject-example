package com.krickert.testcontainers.chunker;

import com.krickert.testcontainers.module.AbstractModuleTestResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test resource provider for the Chunker module.
 * Provides a containerized chunker service for integration testing.
 */
public class ChunkerTestResourceProvider extends AbstractModuleTestResourceProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(ChunkerTestResourceProvider.class);
    private static final String MODULE_NAME = "chunker";
    private static final String DEFAULT_IMAGE = "chunker:latest";
    
    @Override
    protected String getModuleName() {
        return MODULE_NAME;
    }
    
    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }
}