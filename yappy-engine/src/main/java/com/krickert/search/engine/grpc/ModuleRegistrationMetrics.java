package com.krickert.search.engine.grpc;

/**
 * Interface for module registration metrics and monitoring.
 */
public interface ModuleRegistrationMetrics {
    
    /**
     * Gets the count of registered modules.
     * @return the number of registered modules
     */
    int getRegisteredModuleCount();
}