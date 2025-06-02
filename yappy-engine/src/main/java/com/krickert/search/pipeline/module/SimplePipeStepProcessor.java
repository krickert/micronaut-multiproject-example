package com.krickert.search.pipeline.module;

import com.krickert.search.model.PipeStream;
import java.util.Map;
import java.util.Optional;

/**
 * Simplified interface for module developers to implement pipeline step processors.
 * The engine handles all registration, discovery, health checks, and failover logic.
 * 
 * Developers only need to:
 * 1. Implement this interface
 * 2. Add @YappyModule annotation
 * 3. Focus on their business logic
 */
public interface SimplePipeStepProcessor {
    
    /**
     * Process a pipeline stream with the given configuration.
     * 
     * @param input The input stream to process
     * @param config Configuration map (uses defaults if not provided)
     * @return The processed stream
     */
    PipeStream process(PipeStream input, Map<String, String> config);
    
    /**
     * Get default configuration values.
     * These are used when no configuration is provided.
     * 
     * @return Default configuration as a map
     */
    default Map<String, String> getDefaultConfig() {
        return Map.of();
    }
    
    /**
     * Optional: Provide a JSON schema for configuration validation.
     * 
     * @return JSON schema string if validation is needed
     */
    default Optional<String> getConfigSchema() {
        return Optional.empty();
    }
    
    /**
     * Optional: Custom health check logic.
     * By default, the engine will use a simple gRPC health check.
     * 
     * @return true if the module is healthy
     */
    default boolean isHealthy() {
        return true;
    }
    
    /**
     * Optional: Provide metadata for enhanced security/routing.
     * 
     * @return Additional metadata for the module
     */
    default Map<String, String> getMetadata() {
        return Map.of();
    }
}