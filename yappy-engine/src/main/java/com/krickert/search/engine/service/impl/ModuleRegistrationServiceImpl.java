package com.krickert.search.engine.service.impl;

import com.krickert.search.engine.service.ModuleRegistrationService;
import com.krickert.search.grpc.ModuleInfo;
import com.krickert.search.grpc.ModuleHealthStatus;
import com.krickert.search.grpc.RegistrationStatus;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of ModuleRegistrationService that manages module registrations in memory.
 * In production, this would likely use Consul or another service registry.
 */
@Singleton
public class ModuleRegistrationServiceImpl implements ModuleRegistrationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleRegistrationServiceImpl.class);
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 30;
    
    private final Map<String, RegisteredModule> registeredModules = new ConcurrentHashMap<>();
    
    @Override
    public Mono<RegistrationStatus> registerModule(ModuleInfo moduleInfo) {
        return Mono.fromCallable(() -> {
            String serviceId = moduleInfo.getServiceId();
            
            RegisteredModule module = new RegisteredModule(
                serviceId,
                moduleInfo,
                Instant.now()
            );
            
            registeredModules.put(serviceId, module);
            
            LOG.info("Registered module: {} with service ID: {}", 
                moduleInfo.getServiceName(), serviceId);
            
            return RegistrationStatus.newBuilder()
                .setSuccess(true)
                .setMessage("Module registered successfully")
                .build();
        });
    }
    
    @Override
    public Mono<Boolean> unregisterModule(String moduleId) {
        return Mono.fromCallable(() -> {
            RegisteredModule removed = registeredModules.remove(moduleId);
            if (removed != null) {
                LOG.info("Unregistered module: {}", moduleId);
                return true;
            }
            LOG.warn("Attempted to unregister non-existent module: {}", moduleId);
            return false;
        });
    }
    
    @Override
    public Mono<ModuleHealthStatus> getModuleHealth(String moduleId) {
        return Mono.fromCallable(() -> {
            RegisteredModule module = registeredModules.get(moduleId);
            if (module == null) {
                return ModuleHealthStatus.newBuilder()
                    .setServiceId(moduleId)
                    .setIsHealthy(false)
                    .setHealthDetails("Module not found")
                    .build();
            }
            
            boolean isHealthy = isModuleHealthy(module);
            
            return ModuleHealthStatus.newBuilder()
                .setServiceId(moduleId)
                .setServiceName(module.moduleInfo.getServiceName())
                .setIsHealthy(isHealthy)
                .setHealthDetails(isHealthy ? "Module is healthy" : "Module heartbeat timeout")
                .build();
        });
    }
    
    @Override
    public Flux<ModuleInfo> listRegisteredModules() {
        return Flux.fromIterable(registeredModules.values())
            .filter(this::isModuleHealthy)
            .map(module -> module.moduleInfo);
    }
    
    @Override
    public Mono<Boolean> isModuleRegistered(String moduleId) {
        return Mono.fromCallable(() -> registeredModules.containsKey(moduleId));
    }
    
    @Override
    public Mono<Boolean> updateHeartbeat(String moduleId) {
        return Mono.fromCallable(() -> {
            RegisteredModule module = registeredModules.get(moduleId);
            if (module != null) {
                module.lastHeartbeat = Instant.now();
                LOG.debug("Updated heartbeat for module: {}", moduleId);
                return true;
            }
            LOG.warn("Attempted to update heartbeat for non-existent module: {}", moduleId);
            return false;
        });
    }
    
    private String generateServiceId(String moduleId) {
        return moduleId + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
    
    private boolean isModuleHealthy(RegisteredModule module) {
        Instant timeout = Instant.now().minusSeconds(HEARTBEAT_TIMEOUT_SECONDS);
        return module.lastHeartbeat.isAfter(timeout);
    }
    
    /**
     * Internal class to track registered modules.
     */
    private static class RegisteredModule {
        final String serviceId;
        final ModuleInfo moduleInfo;
        volatile Instant lastHeartbeat;
        
        RegisteredModule(String serviceId, ModuleInfo moduleInfo, Instant lastHeartbeat) {
            this.serviceId = serviceId;
            this.moduleInfo = moduleInfo;
            this.lastHeartbeat = lastHeartbeat;
        }
    }
}