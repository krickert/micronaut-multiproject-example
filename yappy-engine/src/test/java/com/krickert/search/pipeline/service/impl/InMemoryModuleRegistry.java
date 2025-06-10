package com.krickert.search.pipeline.service.impl;

import com.krickert.search.pipeline.service.ModuleRegistry;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ModuleRegistry for testing.
 */
public class InMemoryModuleRegistry implements ModuleRegistry {
    
    private final Map<String, ModuleInfo> modules = new ConcurrentHashMap<>();
    
    @Override
    public Mono<RegisterModuleResponse> registerModule(RegisterModuleRequest request) {
        return Mono.fromSupplier(() -> {
            String serviceId = request.getInstanceServiceName() + "-" + UUID.randomUUID();
            
            ModuleInfo moduleInfo = new ModuleInfo(
                    serviceId,
                    request.getImplementationId(),
                    request.getHost(),
                    request.getPort(),
                    request.getHealthCheckEndpoint(),
                    HealthStatus.UNKNOWN,
                    request.getAdditionalTagsMap(),
                    calculateDigest(request.getInstanceCustomConfigJson())
            );
            
            modules.put(serviceId, moduleInfo);
            
            return RegisterModuleResponse.newBuilder()
                    .setSuccess(true)
                    .setMessage("Module registered successfully")
                    .setRegisteredServiceId(serviceId)
                    .setCalculatedConfigDigest(moduleInfo.configDigest())
                    .build();
        });
    }
    
    @Override
    public Mono<Void> unregisterModule(String moduleId) {
        return Mono.fromRunnable(() -> modules.remove(moduleId));
    }
    
    @Override
    public Mono<ModuleInfo> getModule(String moduleId) {
        return Mono.justOrEmpty(modules.get(moduleId));
    }
    
    @Override
    public Flux<ModuleInfo> listModules() {
        return Flux.fromIterable(modules.values());
    }
    
    @Override
    public Mono<Void> updateHealthStatus(String moduleId, HealthStatus status) {
        return Mono.fromRunnable(() -> {
            ModuleInfo existing = modules.get(moduleId);
            if (existing != null) {
                modules.put(moduleId, new ModuleInfo(
                        existing.moduleId(),
                        existing.implementationId(),
                        existing.host(),
                        existing.port(),
                        existing.healthEndpoint(),
                        status,
                        existing.metadata(),
                        existing.configDigest()
                ));
            }
        });
    }
    
    @Override
    public Mono<Boolean> isRegistered(String moduleId) {
        return Mono.just(modules.containsKey(moduleId));
    }
    
    private String calculateDigest(String configJson) {
        // Simple hash for testing
        return "digest-" + configJson.hashCode();
    }
    
    // Test helper methods
    public void clear() {
        modules.clear();
    }
    
    public int getModuleCount() {
        return modules.size();
    }
}