package com.krickert.search.pipeline.module;

import io.micronaut.context.annotation.Requires;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Health indicator for module discovery service.
 * Reports on the health and status of discovered modules.
 */
@Singleton
@Requires(property = "yappy.module.discovery.enabled", value = "true")
@Requires(beans = ModuleDiscoveryService.class)
public class ModuleDiscoveryHealthIndicator implements HealthIndicator {
    
    private final ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    public ModuleDiscoveryHealthIndicator(ModuleDiscoveryService moduleDiscoveryService) {
        this.moduleDiscoveryService = moduleDiscoveryService;
    }
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            
            try {
                Map<String, ModuleDiscoveryService.ModuleStatus> moduleStatuses = 
                        moduleDiscoveryService.getModuleStatuses();
                
                details.put("discovered_modules", moduleStatuses.size());
                
                int healthyCount = 0;
                Map<String, Object> moduleDetails = new HashMap<>();
                
                for (Map.Entry<String, ModuleDiscoveryService.ModuleStatus> entry : moduleStatuses.entrySet()) {
                    ModuleDiscoveryService.ModuleStatus status = entry.getValue();
                    
                    Map<String, Object> moduleInfo = new HashMap<>();
                    moduleInfo.put("pipe_step_name", status.getPipeStepName());
                    moduleInfo.put("healthy", status.isHealthy());
                    moduleInfo.put("instances", status.getInstanceCount());
                    
                    moduleDetails.put(entry.getKey(), moduleInfo);
                    
                    if (status.isHealthy()) {
                        healthyCount++;
                    }
                }
                
                details.put("healthy_modules", healthyCount);
                details.put("modules", moduleDetails);
                
                // Determine overall health
                HealthStatus status;
                String message;
                
                if (moduleStatuses.isEmpty()) {
                    status = HealthStatus.UP;
                    message = "No modules discovered yet";
                } else if (healthyCount == moduleStatuses.size()) {
                    status = HealthStatus.UP;
                    message = String.format("All %d modules are healthy", moduleStatuses.size());
                } else if (healthyCount > 0) {
                    status = HealthStatus.UP;
                    message = String.format("%d of %d modules are healthy", healthyCount, moduleStatuses.size());
                } else {
                    status = HealthStatus.DOWN;
                    message = "No healthy modules available";
                }
                
                return HealthResult.builder("module-discovery", status)
                        .details(details)
                        .build();
                        
            } catch (Exception e) {
                details.put("error", e.getMessage());
                return HealthResult.builder("module-discovery", HealthStatus.DOWN)
                        .details(details)
                        .build();
            }
        });
    }
}