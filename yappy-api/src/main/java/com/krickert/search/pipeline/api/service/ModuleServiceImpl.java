package com.krickert.search.pipeline.api.service;

import com.krickert.search.config.consul.service.ConsulModuleRegistrationService;
import com.krickert.search.config.consul.service.ConsulModuleRegistrationService.HealthCheckConfig;
import com.krickert.search.config.consul.service.ConsulModuleRegistrationService.ServiceInfo;
import com.krickert.search.pipeline.api.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Singleton
public class ModuleServiceImpl implements ModuleService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleServiceImpl.class);
    
    private final ConsulModuleRegistrationService registrationService;
    
    public ModuleServiceImpl(ConsulModuleRegistrationService registrationService) {
        this.registrationService = registrationService;
    }
    
    @Override
    public Flux<ModuleInfo> listModules(String cluster) {
        return Mono.fromCallable(() -> {
            Map<String, ServiceInfo> services = registrationService.getServicesByTag("yappy-module");
            List<ModuleInfo> modules = new ArrayList<>();
            
            for (Map.Entry<String, ServiceInfo> entry : services.entrySet()) {
                ServiceInfo service = entry.getValue();
                modules.add(createModuleInfo(service));
            }
            
            return modules;
        })
        .flatMapMany(Flux::fromIterable)
        .doOnSubscribe(s -> LOG.debug("Listing modules for cluster: {}", cluster));
    }
    
    @Override
    public Mono<ModuleInfo> getModule(String cluster, String moduleId) {
        return Mono.fromCallable(() -> registrationService.getService(moduleId))
            .map(optService -> optService.map(this::createModuleInfo).orElse(null))
            .doOnSubscribe(s -> LOG.debug("Getting module {} for cluster: {}", moduleId, cluster));
    }
    
    @Override
    public Mono<ModuleRegistrationResponse> registerModule(String cluster, ModuleRegistrationRequest request) {
        return Mono.fromCallable(() -> {
            List<String> tags = new ArrayList<>();
            tags.add("yappy-module");
            tags.add("cluster:" + cluster);
            tags.add("version:" + request.version());
            
            if (request.capabilities() != null) {
                request.capabilities().forEach(cap -> tags.add("capability:" + cap));
            }
            
            if (request.inputTypes() != null) {
                request.inputTypes().forEach(type -> tags.add("input-type:" + type));
            }
            
            if (request.outputTypes() != null) {
                request.outputTypes().forEach(type -> tags.add("output-type:" + type));
            }
            
            // Default to gRPC health check for modules
            HealthCheckConfig healthCheck = request.healthCheckPath() != null && !request.healthCheckPath().isEmpty()
                ? HealthCheckConfig.http(request.healthCheckPath())
                : HealthCheckConfig.grpc();
            
            boolean success = registrationService.registerService(
                request.serviceId(),
                request.name(),
                request.host(),
                request.port(),
                tags,
                healthCheck
            );
            
            if (success) {
                return new ModuleRegistrationResponse(
                    request.serviceId(),
                    "registered",
                    Instant.now(),
                    "consul-" + request.serviceId(),
                    "Module registered successfully"
                );
            } else {
                throw new RuntimeException("Failed to register module with Consul");
            }
        })
        .doOnSubscribe(s -> LOG.info("Registering module {} for cluster: {}", request.serviceId(), cluster))
        .doOnError(e -> LOG.error("Failed to register module {}: {}", request.serviceId(), e.getMessage()));
    }
    
    @Override
    public Mono<ModuleInfo> updateModule(String cluster, String moduleId, ModuleUpdateRequest request) {
        // TODO: Implement actual module update in Consul
        return Mono.just(new ModuleInfo(
            moduleId,
            request.name() != null ? request.name() : "Module " + moduleId,
            request.description() != null ? request.description() : "No description",
            request.version() != null ? request.version() : "1.0.0",
            1,
            "healthy",
            request.capabilities() != null ? request.capabilities() : Collections.emptyList(),
            request.inputTypes() != null ? request.inputTypes() : Collections.emptyList(),
            request.outputTypes() != null ? request.outputTypes() : Collections.emptyList(),
            request.configSchema() != null ? request.configSchema() : Collections.emptyMap(),
            Instant.now(),
            Instant.now()
        ));
    }
    
    @Override
    public Mono<Void> deregisterModule(String cluster, String moduleId) {
        return Mono.fromCallable(() -> {
            boolean success = registrationService.deregisterService(moduleId);
            if (!success) {
                throw new RuntimeException("Failed to deregister module from Consul");
            }
            return null;
        })
        .then()
        .doOnSubscribe(s -> LOG.info("Deregistering module {} from cluster: {}", moduleId, cluster))
        .doOnError(e -> LOG.error("Failed to deregister module {}: {}", moduleId, e.getMessage()));
    }
    
    @Override
    public Mono<ModuleHealthStatus> checkHealth(String cluster, String moduleId) {
        // TODO: Implement actual health check via gRPC
        return Mono.just(new ModuleHealthStatus(
            moduleId,
            "healthy",
            Instant.now(),
            50L,
            Collections.emptyList(),
            Collections.emptyMap(),
            null
        ));
    }
    
    @Override
    public Mono<ModuleTestResponse> testModule(String cluster, String moduleId, ModuleTestRequest request) {
        // TODO: Implement actual module test via gRPC
        return Mono.just(new ModuleTestResponse(
            true,
            java.time.Duration.ofMillis(100),
            Collections.emptyMap(),
            "application/json",
            moduleId,
            "instance-1",
            null,
            Collections.emptyMap()
        ));
    }
    
    @Override
    public Flux<ModuleTemplate> getTemplates() {
        // TODO: Implement module template retrieval
        return Flux.empty();
    }
    
    private ModuleInfo createModuleInfo(ServiceInfo service) {
        String version = service.getTagValue("version:");
        String moduleType = service.getTagValue("module-type:");
        
        List<String> capabilities = service.tags().stream()
            .filter(tag -> tag.startsWith("capability:"))
            .map(tag -> tag.substring("capability:".length()))
            .toList();
        
        return new ModuleInfo(
            service.id(),
            service.name(),
            "Module " + service.name(), // Description would come from metadata
            version != null ? version : "unknown",
            1, // Instance count - would need to query health checks
            "healthy", // Status - would need to query health checks
            capabilities,
            Collections.emptyList(), // Input types - would come from metadata
            Collections.emptyList(), // Output types - would come from metadata
            Collections.emptyMap(), // Config schema - would come from metadata
            Instant.now(), // Registered time - would come from metadata
            Instant.now() // Updated time - would come from metadata
        );
    }
}