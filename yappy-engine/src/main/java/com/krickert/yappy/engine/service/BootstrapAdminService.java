package com.krickert.yappy.engine.service;

import com.krickert.yappy.engine.controller.admin.dto.*;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service interface for admin operations that handles bootstrap configuration
 * without directly manipulating Consul KV or file system.
 */
public interface BootstrapAdminService {
    
    /**
     * Gets the current Consul configuration from bootstrap properties.
     */
    Mono<ConsulConfigResponse> getCurrentConsulConfiguration();
    
    /**
     * Updates the Consul configuration in bootstrap properties.
     */
    Mono<SetConsulConfigResponse> setConsulConfiguration(SetConsulConfigRequest request);
    
    /**
     * Lists available Yappy clusters from Consul.
     */
    Mono<List<YappyClusterInfo>> listAvailableYappyClusters();
    
    /**
     * Creates a new Yappy cluster and selects it.
     */
    Mono<CreateClusterResponse> createNewYappyCluster(CreateClusterRequest request);
    
    /**
     * Selects an existing cluster as the active cluster.
     */
    Mono<SelectClusterResponse> selectActiveYappyCluster(SelectClusterRequest request);
}