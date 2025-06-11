package com.krickert.testcontainers.tika;

import com.krickert.testcontainers.module.AbstractModuleTestResourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Test resource provider for the Tika Parser module.
 * Provides a containerized tika-parser service for integration testing.
 */
public class TikaTestResourceProvider extends AbstractModuleTestResourceProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(TikaTestResourceProvider.class);
    private static final String MODULE_NAME = "tika";
    private static final String DEFAULT_IMAGE = "yappy-platform-build/tika-parser:latest";
    
    @Override
    protected String getModuleName() {
        return MODULE_NAME;
    }
    
    @Override
    protected String getDefaultImageName() {
        return DEFAULT_IMAGE;
    }
    
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        // Add tika-specific properties in addition to base properties
        List<String> baseProperties = super.getResolvableProperties(propertyEntries, testResourcesConfig);
        
        // Also support the legacy property names that tests might be using
        List<String> tikaProperties = Arrays.asList(
            "tika.grpc.host",
            "tika.grpc.port"
        );
        
        // Combine both lists
        List<String> allProperties = new java.util.ArrayList<>(baseProperties);
        allProperties.addAll(tikaProperties);
        
        return allProperties;
    }
    
    @Override
    protected java.util.Optional<String> resolveProperty(String propertyName, com.krickert.testcontainers.module.ModuleContainer container) {
        // First check base properties
        java.util.Optional<String> baseResult = super.resolveProperty(propertyName, container);
        if (baseResult.isPresent()) {
            return baseResult;
        }
        
        // Handle legacy tika-specific properties
        if ("tika.grpc.host".equals(propertyName)) {
            return java.util.Optional.of(container.getHost());
        }
        if ("tika.grpc.port".equals(propertyName)) {
            return java.util.Optional.of(String.valueOf(container.getMappedPort(50051)));
        }
        
        return java.util.Optional.empty();
    }
    
    @Override
    protected boolean shouldAnswer(String propertyName, Map<String, Object> properties, Map<String, Object> testResourcesConfig) {
        // Answer for both "tika.*" and legacy "tika.grpc.*" properties
        return propertyName != null && 
               (propertyName.startsWith(getModuleName() + ".") || 
                propertyName.startsWith("tika.grpc."));
    }
}