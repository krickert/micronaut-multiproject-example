package com.krickert.search.engine.health;

import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.yappy.registration.api.HealthCheckType;
import reactor.core.publisher.Mono;

/**
 * Interface for monitoring module health.
 */
public interface ModuleHealthMonitor {
    
    /**
     * Starts monitoring the health of a module.
     * 
     * @param moduleInfo The module to monitor
     * @param healthCheckType The type of health check to perform
     * @param healthCheckEndpoint The endpoint for health checks (if applicable)
     * @return A Mono that completes when monitoring is started
     */
    Mono<Void> startMonitoring(ModuleInfo moduleInfo, HealthCheckType healthCheckType, String healthCheckEndpoint);
    
    /**
     * Stops monitoring a module.
     * 
     * @param moduleId The ID of the module to stop monitoring
     * @return A Mono that completes when monitoring is stopped
     */
    Mono<Void> stopMonitoring(String moduleId);
    
    /**
     * Performs an immediate health check on a module.
     * 
     * @param moduleId The ID of the module to check
     * @return The current health status of the module
     */
    Mono<ModuleHealthStatus> checkHealth(String moduleId);
    
    /**
     * Gets the current health status of a module without performing a check.
     * 
     * @param moduleId The ID of the module
     * @return The cached health status of the module
     */
    Mono<ModuleHealthStatus> getCachedHealthStatus(String moduleId);
    
    /**
     * Registers a listener for health status changes.
     * 
     * @param listener The listener to register
     */
    void addHealthChangeListener(HealthChangeListener listener);
    
    /**
     * Removes a health change listener.
     * 
     * @param listener The listener to remove
     */
    void removeHealthChangeListener(HealthChangeListener listener);
    
    /**
     * Interface for listening to health status changes.
     */
    interface HealthChangeListener {
        /**
         * Called when a module's health status changes.
         * 
         * @param moduleId The ID of the module
         * @param newStatus The new health status
         * @param previousStatus The previous health status
         */
        void onHealthChange(String moduleId, ModuleHealthStatus newStatus, ModuleHealthStatus previousStatus);
    }
}