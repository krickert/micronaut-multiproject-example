package com.krickert.testcontainers.embedder;

import com.krickert.testcontainers.module.AbstractModuleTestResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test resource provider for the Embedder module.
 * Provides a containerized embedder service for integration testing.
 * 
 * The embedder module runs on gRPC port 50051 by default and provides
 * text embedding services using various pre-configured models.
 */
public class EmbedderTestResourceProvider extends AbstractModuleTestResourceProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(EmbedderTestResourceProvider.class);
    private static final String MODULE_NAME = "embedder";
    private static final String DEFAULT_IMAGE = "embedder:latest";
    
    @Override
    protected String getModuleName() {
        return MODULE_NAME;
    }
    
    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }
}