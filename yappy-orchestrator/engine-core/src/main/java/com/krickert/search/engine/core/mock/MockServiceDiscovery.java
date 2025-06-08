package com.krickert.search.engine.core.mock;

import com.krickert.search.engine.core.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple mock implementation of ServiceDiscovery for testing.
 * 
 * This allows testing without Consul.
 */
public class MockServiceDiscovery implements ServiceDiscovery {
    
    private final Map<String, Set<ServiceInstance>> services = new ConcurrentHashMap<>();
    private final AtomicInteger portCounter = new AtomicInteger(50000);
    
    @Override
    public Mono<ServiceInstance> getHealthyInstance(String serviceName) {
        return Mono.justOrEmpty(services.get(serviceName))
            .filter(instances -> !instances.isEmpty())
            .map(instances -> instances.iterator().next())
            .switchIfEmpty(Mono.error(new RuntimeException("No healthy instances for " + serviceName)));
    }
    
    @Override
    public Flux<ServiceInstance> getAllHealthyInstances(String serviceName) {
        return Flux.fromIterable(services.getOrDefault(serviceName, Set.of()));
    }
    
    @Override
    public Flux<ServiceInstanceUpdate> watchService(String serviceName) {
        // For mock, just return empty flux
        return Flux.empty();
    }
    
    @Override
    public Mono<Void> registerService(ServiceRegistration registration) {
        return Mono.fromRunnable(() -> {
            ServiceInstance instance = new ServiceInstance(
                registration.serviceId(),
                registration.serviceName(),
                registration.host(),
                registration.port(),
                HealthStatus.HEALTHY,
                registration.tags(),
                registration.metadata()
            );
            
            services.computeIfAbsent(registration.serviceName(), k -> ConcurrentHashMap.newKeySet())
                .add(instance);
        });
    }
    
    @Override
    public Mono<Void> deregisterService(String serviceId) {
        return Mono.fromRunnable(() -> {
            services.values().forEach(instances -> 
                instances.removeIf(instance -> instance.serviceId().equals(serviceId))
            );
        });
    }
    
    /**
     * Helper method to add a mock service for testing.
     */
    public void addMockService(String serviceName, String host) {
        int port = portCounter.incrementAndGet();
        ServiceRegistration registration = ServiceRegistration.builder()
            .serviceId(serviceName + "-" + port)
            .serviceName(serviceName)
            .host(host)
            .port(port)
            .healthCheck(HealthCheckConfig.grpc(serviceName))
            .tags(Set.of("mock", "test"))
            .metadata(Map.of("version", "1.0.0"))
            .build();
            
        registerService(registration).block();
    }
}