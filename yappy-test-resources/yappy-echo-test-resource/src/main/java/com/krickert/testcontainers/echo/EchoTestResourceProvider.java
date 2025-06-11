package com.krickert.testcontainers.echo;

import com.krickert.testcontainers.module.AbstractModuleTestResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test resource provider for the Echo module.
 * Provides a containerized echo service for integration testing.
 * 
 * The echo module will be configured with:
 * - gRPC port: 50051 (mapped dynamically)
 * - HTTP port: 8080 (mapped dynamically)
 * - Environment variables will override the application.yml defaults
 */
public class EchoTestResourceProvider extends AbstractModuleTestResourceProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(EchoTestResourceProvider.class);
    private static final String MODULE_NAME = "echo";
    private static final String DEFAULT_IMAGE = "echo:latest";
    
    @Override
    protected String getModuleName() {
        return MODULE_NAME;
    }
    
    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }
}