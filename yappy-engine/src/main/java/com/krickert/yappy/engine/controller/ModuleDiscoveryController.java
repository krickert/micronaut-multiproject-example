package com.krickert.yappy.engine.controller;

import com.krickert.search.pipeline.module.ModuleDiscoveryService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for module discovery information.
 * Provides endpoints to view discovered modules and trigger discovery.
 */
@Controller("/api/modules")
@Requires(property = "yappy.module.discovery.enabled", value = "true")
@Requires(beans = ModuleDiscoveryService.class)
public class ModuleDiscoveryController {
    
    private final ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    public ModuleDiscoveryController(ModuleDiscoveryService moduleDiscoveryService) {
        this.moduleDiscoveryService = moduleDiscoveryService;
    }
    
    /**
     * Get all discovered modules
     */
    @Get
    public HttpResponse<ModulesResponse> getModules() {
        Map<String, ModuleDiscoveryService.ModuleStatus> statuses = 
                moduleDiscoveryService.getModuleStatuses();
        
        List<ModuleInfo> modules = statuses.entrySet().stream()
                .map(entry -> new ModuleInfo(
                        entry.getKey(),
                        entry.getValue().getPipeStepName(),
                        entry.getValue().isHealthy(),
                        entry.getValue().getInstanceCount(),
                        entry.getValue().getMetadata()
                ))
                .collect(Collectors.toList());
        
        return HttpResponse.ok(new ModulesResponse(modules));
    }
    
    /**
     * Get information about a specific module
     */
    @Get("/{moduleName}")
    public HttpResponse<ModuleInfo> getModule(@PathVariable String moduleName) {
        Map<String, ModuleDiscoveryService.ModuleStatus> statuses = 
                moduleDiscoveryService.getModuleStatuses();
        
        ModuleDiscoveryService.ModuleStatus status = statuses.get(moduleName);
        if (status == null) {
            return HttpResponse.notFound();
        }
        
        ModuleInfo info = new ModuleInfo(
                moduleName,
                status.getPipeStepName(),
                status.isHealthy(),
                status.getInstanceCount(),
                status.getMetadata()
        );
        
        return HttpResponse.ok(info);
    }
    
    /**
     * Get healthy instances for a module (for load balancing/failover)
     */
    @Get("/{moduleName}/instances")
    public Mono<InstancesResponse> getModuleInstances(@PathVariable String moduleName) {
        return moduleDiscoveryService.getHealthyInstances(moduleName)
                .map(instances -> new InstancesResponse(moduleName, instances));
    }
    
    /**
     * Trigger module discovery manually
     */
    @Post("/discover")
    public HttpResponse<DiscoveryResponse> triggerDiscovery() {
        moduleDiscoveryService.discoverAndRegisterModules();
        
        return HttpResponse.ok(new DiscoveryResponse(
                "Discovery triggered",
                moduleDiscoveryService.getModuleStatuses().size()
        ));
    }
    
    // Response DTOs
    
    @Serdeable
    public static class ModulesResponse {
        private final List<ModuleInfo> modules;
        
        public ModulesResponse(List<ModuleInfo> modules) {
            this.modules = modules;
        }
        
        public List<ModuleInfo> getModules() {
            return modules;
        }
    }
    
    @Serdeable
    public static class ModuleInfo {
        private final String serviceName;
        private final String pipeStepName;
        private final boolean healthy;
        private final int instanceCount;
        private final Map<String, String> metadata;
        
        public ModuleInfo(String serviceName, String pipeStepName, boolean healthy, 
                         int instanceCount, Map<String, String> metadata) {
            this.serviceName = serviceName;
            this.pipeStepName = pipeStepName;
            this.healthy = healthy;
            this.instanceCount = instanceCount;
            this.metadata = metadata;
        }
        
        // Getters
        public String getServiceName() { return serviceName; }
        public String getPipeStepName() { return pipeStepName; }
        public boolean isHealthy() { return healthy; }
        public int getInstanceCount() { return instanceCount; }
        public Map<String, String> getMetadata() { return metadata; }
    }
    
    @Serdeable
    public static class InstancesResponse {
        private final String moduleName;
        private final List<String> instances;
        
        public InstancesResponse(String moduleName, List<String> instances) {
            this.moduleName = moduleName;
            this.instances = instances;
        }
        
        public String getModuleName() { return moduleName; }
        public List<String> getInstances() { return instances; }
    }
    
    @Serdeable
    public static class DiscoveryResponse {
        private final String message;
        private final int discoveredModules;
        
        public DiscoveryResponse(String message, int discoveredModules) {
            this.message = message;
            this.discoveredModules = discoveredModules;
        }
        
        public String getMessage() { return message; }
        public int getDiscoveredModules() { return discoveredModules; }
    }
}