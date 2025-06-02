package com.krickert.search.pipeline.module;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.discovery.event.ServiceReadyEvent;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens for service ready event and triggers initial module discovery.
 * This ensures modules are discovered as soon as the engine starts.
 */
@Singleton
@Requires(property = "yappy.module.discovery.enabled", value = "true")
@Requires(beans = ModuleDiscoveryService.class)
public class ModuleDiscoveryStartupListener implements ApplicationEventListener<ServiceReadyEvent> {
    
    private static final Logger LOG = LoggerFactory.getLogger(ModuleDiscoveryStartupListener.class);
    
    private final ModuleDiscoveryService moduleDiscoveryService;
    
    @Inject
    public ModuleDiscoveryStartupListener(ModuleDiscoveryService moduleDiscoveryService) {
        this.moduleDiscoveryService = moduleDiscoveryService;
    }
    
    @Override
    @ExecuteOn(TaskExecutors.IO)
    public void onApplicationEvent(ServiceReadyEvent event) {
        LOG.info("Service ready event received. Initiating module discovery...");
        
        try {
            // Trigger initial discovery
            moduleDiscoveryService.discoverAndRegisterModules();
            
            // Log discovered modules
            var moduleStatuses = moduleDiscoveryService.getModuleStatuses();
            if (moduleStatuses.isEmpty()) {
                LOG.info("No modules discovered during startup. Modules can still be discovered later.");
            } else {
                LOG.info("Discovered {} modules during startup:", moduleStatuses.size());
                moduleStatuses.forEach((name, status) -> {
                    LOG.info("  - {} ({}): {} instances", 
                            name, 
                            status.getPipeStepName(),
                            status.getInstanceCount());
                });
            }
        } catch (Exception e) {
            LOG.error("Error during startup module discovery", e);
            // Don't fail startup if module discovery fails
        }
    }
}