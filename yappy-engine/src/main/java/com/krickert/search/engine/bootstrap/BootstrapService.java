package com.krickert.search.engine.bootstrap;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import reactor.core.publisher.Mono;

/**
 * Service responsible for bootstrapping the engine with initial configuration.
 * This includes setting up initial cluster configurations, registering default modules,
 * and preparing the engine for operation.
 */
public interface BootstrapService {
    
    /**
     * Initialize the engine with bootstrap configuration.
     * This method should be idempotent - calling it multiple times should not cause issues.
     * 
     * @param clusterName the name of the cluster to bootstrap
     * @return a Mono that completes when bootstrap is finished
     */
    Mono<Void> bootstrap(String clusterName);
    
    /**
     * Create and store a minimal cluster configuration if none exists.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono containing the created or existing configuration
     */
    Mono<PipelineClusterConfig> ensureClusterConfiguration(String clusterName);
    
    /**
     * Register default modules that are essential for basic operation.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono that completes when all default modules are registered
     */
    Mono<Void> registerDefaultModules(String clusterName);
    
    /**
     * Check if the cluster has been bootstrapped.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono containing true if bootstrapped, false otherwise
     */
    Mono<Boolean> isBootstrapped(String clusterName);
    
    /**
     * Reset the bootstrap state for a cluster.
     * This is primarily for testing and should be used with caution.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono that completes when reset is finished
     */
    Mono<Void> resetBootstrap(String clusterName);
}