package com.krickert.search.engine.bootstrap;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.event.ApplicationStartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

/**
 * Listener that triggers engine bootstrap on application startup.
 * This replaces the old YappyEngineBootstrapper functionality.
 */
@Singleton
public class EngineBootstrapListener implements ApplicationEventListener<ApplicationStartupEvent> {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineBootstrapListener.class);
    
    private final BootstrapService bootstrapService;
    private final SeedDataService seedDataService;
    private final String clusterName;
    private final boolean bootstrapEnabled;
    private final boolean seedingEnabled;
    private final String seedTemplate;
    
    @Inject
    public EngineBootstrapListener(
            BootstrapService bootstrapService,
            @Nullable SeedDataService seedDataService,
            @Value("${yappy.cluster.name:default-cluster}") String clusterName,
            @Value("${yappy.engine.bootstrap.enabled:true}") boolean bootstrapEnabled,
            @Value("${yappy.engine.seeding.enabled:false}") boolean seedingEnabled,
            @Value("${yappy.engine.seeding.template:test}") String seedTemplate) {
        this.bootstrapService = bootstrapService;
        this.seedDataService = seedDataService;
        this.clusterName = clusterName;
        this.bootstrapEnabled = bootstrapEnabled;
        this.seedingEnabled = seedingEnabled;
        this.seedTemplate = seedTemplate;
    }
    
    @Override
    public void onApplicationEvent(ApplicationStartupEvent event) {
        if (!bootstrapEnabled) {
            LOG.info("Engine bootstrap is disabled");
            return;
        }
        
        LOG.info("Starting engine bootstrap for cluster: {}", clusterName);
        
        bootstrapService.bootstrap(clusterName)
                .then(seedDataIfEnabled())
                .subscribe(
                        null,
                        error -> LOG.error("Engine bootstrap failed: {}", error.getMessage(), error),
                        () -> LOG.info("Engine bootstrap completed successfully")
                );
    }
    
    private reactor.core.publisher.Mono<Void> seedDataIfEnabled() {
        if (!seedingEnabled || seedDataService == null) {
            if (seedingEnabled && seedDataService == null) {
                LOG.warn("Seeding is enabled but SeedDataService is not available");
            }
            return reactor.core.publisher.Mono.empty();
        }
        
        LOG.info("Seeding is enabled, using template: {}", seedTemplate);
        return seedDataService.seedCluster(clusterName, seedTemplate);
    }
}