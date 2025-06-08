package com.krickert.search.engine.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for discovering available service instances.
 * 
 * This abstracts the service discovery mechanism (Consul) from the
 * engine implementation. Implementations will use Micronaut's service
 * discovery with Consul integration.
 */
public interface ServiceDiscovery {
    
    /**
     * Get a healthy instance of the specified service.
     * 
     * This will use Consul health checks to ensure only healthy
     * instances are returned. Load balancing strategy (round-robin,
     * least-connections, etc.) is implementation-specific.
     * 
     * @param serviceName The gRPC service name from pipeline config
     * @return Mono with service instance details
     */
    Mono<ServiceInstance> getHealthyInstance(String serviceName);
    
    /**
     * Get all healthy instances of a service.
     * 
     * @param serviceName The gRPC service name
     * @return Flux of all healthy instances
     */
    Flux<ServiceInstance> getAllHealthyInstances(String serviceName);
    
    /**
     * Watch for changes to service instances.
     * 
     * This provides a stream of updates when instances are added,
     * removed, or change health status.
     * 
     * @param serviceName The service to watch
     * @return Flux of service instance updates
     */
    Flux<ServiceInstanceUpdate> watchService(String serviceName);
    
    /**
     * Register a service instance with Consul.
     * 
     * Used by the registration service to add new modules.
     * 
     * @param registration The service registration details
     * @return Mono indicating successful registration
     */
    Mono<Void> registerService(ServiceRegistration registration);
    
    /**
     * Deregister a service instance from Consul.
     * 
     * @param serviceId The unique service instance ID
     * @return Mono indicating successful deregistration
     */
    Mono<Void> deregisterService(String serviceId);
}