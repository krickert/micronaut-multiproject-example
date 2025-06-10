package com.krickert.search.engine.registration;

import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import reactor.core.publisher.Mono;

/**
 * Service interface for module registration with enhanced validation and error handling.
 */
public interface ModuleRegistrationService {
    
    /**
     * Registers a module after performing validation.
     * 
     * @param request the registration request
     * @return a Mono containing the registration response
     */
    Mono<RegisterModuleResponse> registerModule(RegisterModuleRequest request);
    
    /**
     * Deregisters a module by its service ID.
     * 
     * @param serviceId the Consul service ID to deregister
     * @return a Mono that completes when deregistration is done
     */
    Mono<Void> deregisterModule(String serviceId);
    
    /**
     * Checks if a module is currently registered and healthy.
     * 
     * @param serviceId the Consul service ID to check
     * @return a Mono containing true if the module is registered and healthy
     */
    Mono<Boolean> isModuleHealthy(String serviceId);
}