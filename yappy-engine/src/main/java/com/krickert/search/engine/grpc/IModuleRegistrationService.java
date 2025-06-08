package com.krickert.search.engine.grpc;

/**
 * Interface for module registration service operations.
 */
public interface IModuleRegistrationService {
    
    /**
     * Gets the count of registered modules.
     * @return the number of registered modules
     */
    int getRegisteredModuleCount();
}