package com.krickert.search.pipeline.api.service;

import com.krickert.search.pipeline.api.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service interface for module management operations.
 */
public interface ModuleService {
    
    /**
     * List all registered modules in a cluster.
     */
    Flux<ModuleInfo> listModules(String cluster);
    
    /**
     * Get information about a specific module.
     */
    Mono<ModuleInfo> getModule(String cluster, String serviceId);
    
    /**
     * Register a new module.
     */
    Mono<ModuleRegistrationResponse> registerModule(String cluster, ModuleRegistrationRequest request);
    
    /**
     * Update module registration.
     */
    Mono<ModuleInfo> updateModule(String cluster, String serviceId, ModuleUpdateRequest request);
    
    /**
     * Deregister a module.
     */
    Mono<Void> deregisterModule(String cluster, String serviceId);
    
    /**
     * Check health of a module.
     */
    Mono<ModuleHealthStatus> checkHealth(String cluster, String serviceId);
    
    /**
     * Test a module with sample data.
     */
    Mono<ModuleTestResponse> testModule(String cluster, String serviceId, ModuleTestRequest request);
    
    /**
     * Get available module configuration templates.
     */
    Flux<ModuleTemplate> getTemplates();
}