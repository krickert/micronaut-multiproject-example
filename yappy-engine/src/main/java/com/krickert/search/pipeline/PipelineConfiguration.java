package com.krickert.search.pipeline;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Configuration service for managing pipeline configurations.
 * This provides access to pipeline configurations for the test environment.
 */
@Singleton
public class PipelineConfiguration {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineConfiguration.class);
    
    private final Map<String, PipelineConfig> pipelines = new ConcurrentHashMap<>();
    
    public PipelineConfiguration() {
        // Initialize with default or test configurations
    }
    
    /**
     * Store a pipeline configuration.
     * @param pipelineId the pipeline ID
     * @param config the pipeline configuration
     */
    public void setPipelineConfig(String pipelineId, PipelineConfig config) {
        LOG.info("Storing pipeline configuration for: {}", pipelineId);
        pipelines.put(pipelineId, config);
    }
    
    /**
     * Get a pipeline configuration.
     * @param pipelineId the pipeline ID
     * @return the pipeline configuration, or null if not found
     */
    public PipelineConfig getPipelineConfig(String pipelineId) {
        return pipelines.get(pipelineId);
    }
    
    /**
     * Check if a pipeline configuration exists.
     * @param pipelineId the pipeline ID
     * @return true if the configuration exists
     */
    public boolean hasPipelineConfig(String pipelineId) {
        return pipelines.containsKey(pipelineId);
    }
    
    /**
     * Remove a pipeline configuration.
     * @param pipelineId the pipeline ID
     */
    public void removePipelineConfig(String pipelineId) {
        LOG.info("Removing pipeline configuration for: {}", pipelineId);
        pipelines.remove(pipelineId);
    }
    
    /**
     * Get all pipeline IDs.
     * @return set of pipeline IDs
     */
    public Iterable<String> getAllPipelineIds() {
        return pipelines.keySet();
    }
}