package com.krickert.search.engine.bootstrap;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import reactor.core.publisher.Mono;

/**
 * Service for seeding initial data into the engine.
 * This is primarily used for development and testing environments.
 */
public interface SeedDataService {
    
    /**
     * Load a predefined pipeline configuration template.
     * 
     * @param templateName the name of the template (e.g., "minimal", "standard", "complex")
     * @return a Mono containing the pipeline configuration
     */
    Mono<PipelineClusterConfig> loadTemplate(String templateName);
    
    /**
     * Seed the cluster with a specific configuration template.
     * 
     * @param clusterName the name of the cluster
     * @param templateName the name of the template to use
     * @return a Mono that completes when seeding is finished
     */
    Mono<Void> seedCluster(String clusterName, String templateName);
    
    /**
     * Seed the cluster with a custom configuration.
     * 
     * @param config the configuration to seed
     * @return a Mono that completes when seeding is finished
     */
    Mono<Void> seedCluster(PipelineClusterConfig config);
    
    /**
     * Available template names.
     * 
     * @return array of available template names
     */
    String[] getAvailableTemplates();
    
    /**
     * Create a test pipeline configuration with Tika and Chunker modules.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono containing the test configuration
     */
    Mono<PipelineClusterConfig> createTestPipelineConfig(String clusterName);
}