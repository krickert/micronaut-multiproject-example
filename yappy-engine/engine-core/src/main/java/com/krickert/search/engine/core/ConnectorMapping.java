package com.krickert.search.engine.core;

import java.util.Map;

/**
 * Mapping from a source identifier to a pipeline entry point.
 * 
 * This defines how external connectors route into pipelines.
 */
public record ConnectorMapping(
    String sourceIdentifier,
    String description,
    String targetPipeline,
    String initialStep,
    boolean enabled,
    Map<String, String> contextParams,
    Map<String, Object> metadata
) {
    
    /**
     * Check if this mapping is active and can be used.
     */
    public boolean isActive() {
        return enabled;
    }
}