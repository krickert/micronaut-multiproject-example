package com.krickert.search.engine.service;

import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.search.grpc.RegistrationStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for managing module registration with the engine.
 * Modules register to provide processing capabilities to pipelines.
 */
public interface ModuleRegistrationService {
    
    /**
     * Register a module with the engine.
     * 
     * @param moduleInfo Information about the module to register
     * @return Registration status indicating success/failure
     */
    Mono<RegistrationStatus> registerModule(ModuleInfo moduleInfo);
    
    /**
     * Unregister a module from the engine.
     * 
     * @param moduleId The ID of the module to unregister
     * @return Success status
     */
    Mono<Boolean> unregisterModule(String moduleId);
    
    /**
     * Get the health status of a registered module.
     * 
     * @param moduleId The ID of the module
     * @return Health status of the module
     */
    Mono<ModuleHealthStatus> getModuleHealth(String moduleId);
    
    /**
     * List all registered modules.
     * 
     * @return Flux of registered module information
     */
    Flux<ModuleInfo> listRegisteredModules();
    
    /**
     * Check if a module is registered.
     * 
     * @param moduleId The ID of the module
     * @return True if registered, false otherwise
     */
    Mono<Boolean> isModuleRegistered(String moduleId);
    
    /**
     * Update module heartbeat to indicate it's still alive.
     * 
     * @param moduleId The ID of the module
     * @return Success status
     */
    Mono<Boolean> updateHeartbeat(String moduleId);
}