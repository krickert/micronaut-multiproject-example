package com.krickert.search.pipeline.api.service;

import com.krickert.search.config.consul.model.ClusterInfo;
import com.krickert.search.pipeline.api.dto.ClusterStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service interface for cluster management operations.
 */
public interface ClusterService {
    
    /**
     * List all available clusters.
     */
    Flux<ClusterInfo> listClusters();
    
    /**
     * Get detailed information about a specific cluster.
     */
    Mono<ClusterInfo> getCluster(String name);
    
    /**
     * Get the operational status of a cluster.
     */
    Mono<ClusterStatus> getClusterStatus(String name);
    
    /**
     * Create a new cluster with validation.
     */
    Mono<ClusterInfo> createCluster(ClusterInfo clusterInfo);
    
    /**
     * Update an existing cluster configuration.
     */
    Mono<ClusterInfo> updateCluster(String name, ClusterInfo clusterInfo);
    
    /**
     * Delete a cluster (with optional force flag).
     */
    Mono<Void> deleteCluster(String name, boolean force);
    
    /**
     * Set the active cluster.
     */
    Mono<Void> setActiveCluster(String name);
    
    /**
     * Get the currently active cluster.
     */
    Mono<String> getActiveCluster();
}