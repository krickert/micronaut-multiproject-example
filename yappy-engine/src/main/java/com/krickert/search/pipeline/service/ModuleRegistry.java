package com.krickert.search.pipeline.service;

import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Interface for module registry operations.
 * This abstraction allows for easier testing and potential different implementations.
 */
public interface ModuleRegistry {
    
    /**
     * Register a module with the engine.
     * 
     * @param request The module registration request
     * @return A Mono with the registration response
     */
    Mono<RegisterModuleResponse> registerModule(RegisterModuleRequest request);
    
    /**
     * Unregister a module.
     * 
     * @param moduleId The ID of the module to unregister
     * @return A Mono indicating completion
     */
    Mono<Void> unregisterModule(String moduleId);
    
    /**
     * Get information about a registered module.
     * 
     * @param moduleId The module ID
     * @return Module information if found
     */
    Mono<ModuleInfo> getModule(String moduleId);
    
    /**
     * List all registered modules.
     * 
     * @return Flux of all registered modules
     */
    Flux<ModuleInfo> listModules();
    
    /**
     * Update module health status.
     * 
     * @param moduleId The module ID
     * @param status The health status
     * @return A Mono indicating completion
     */
    Mono<Void> updateHealthStatus(String moduleId, HealthStatus status);
    
    /**
     * Check if a module is registered.
     * 
     * @param moduleId The module ID
     * @return true if registered
     */
    Mono<Boolean> isRegistered(String moduleId);
    
    /**
     * Information about a registered module.
     */
    record ModuleInfo(
            String moduleId,
            String implementationId,
            String host,
            int port,
            String healthEndpoint,
            HealthStatus healthStatus,
            Map<String, String> metadata,
            String configDigest
    ) {}
    
    /**
     * Health status of a module.
     */
    enum HealthStatus {
        UNKNOWN,
        HEALTHY,
        UNHEALTHY,
        DEGRADED
    }
}