package com.krickert.search.engine.bootstrap.impl;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.engine.bootstrap.BootstrapService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Secondary;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of BootstrapService for testing and non-Consul environments.
 */
@Singleton
@Secondary
@Requires(property = "consul.client.enabled", value = "false")
public class InMemoryBootstrapService implements BootstrapService {
    
    private static final Logger LOG = LoggerFactory.getLogger(InMemoryBootstrapService.class);
    
    private final ConcurrentHashMap<String, PipelineClusterConfig> clusterConfigs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> bootstrappedClusters = new ConcurrentHashMap<>();
    
    @Override
    public Mono<Void> bootstrap(String clusterName) {
        return Mono.defer(() -> {
            LOG.info("Starting in-memory bootstrap process for cluster: {}", clusterName);
            
            if (bootstrappedClusters.containsKey(clusterName)) {
                LOG.info("Cluster {} is already bootstrapped", clusterName);
                return Mono.empty();
            }
            
            return ensureClusterConfiguration(clusterName)
                    .then(registerDefaultModules(clusterName))
                    .doOnSuccess(v -> {
                        bootstrappedClusters.put(clusterName, true);
                        LOG.info("In-memory bootstrap completed successfully for cluster: {}", clusterName);
                    })
                    .doOnError(error -> {
                        LOG.error("In-memory bootstrap failed for cluster {}: {}", clusterName, error.getMessage(), error);
                    });
        });
    }
    
    @Override
    public Mono<PipelineClusterConfig> ensureClusterConfiguration(String clusterName) {
        return Mono.fromCallable(() -> {
            LOG.info("Ensuring in-memory cluster configuration exists for: {}", clusterName);
            
            return clusterConfigs.computeIfAbsent(clusterName, this::createMinimalClusterConfig);
        });
    }
    
    @Override
    public Mono<Void> registerDefaultModules(String clusterName) {
        return Mono.fromRunnable(() -> {
            LOG.info("Registering default modules in-memory for cluster: {}", clusterName);
            // In-memory implementation doesn't need actual module registration
            // Modules are defined in the configuration
        });
    }
    
    @Override
    public Mono<Boolean> isBootstrapped(String clusterName) {
        return Mono.fromCallable(() -> bootstrappedClusters.containsKey(clusterName));
    }
    
    @Override
    public Mono<Void> resetBootstrap(String clusterName) {
        return Mono.fromRunnable(() -> {
            LOG.warn("Resetting in-memory bootstrap state for cluster: {}", clusterName);
            bootstrappedClusters.remove(clusterName);
            clusterConfigs.remove(clusterName);
        });
    }
    
    private PipelineClusterConfig createMinimalClusterConfig(String clusterName) {
        return new PipelineClusterConfig(
                clusterName,
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                null, // No default pipeline
                Collections.emptySet(), // No allowed Kafka topics
                Collections.emptySet()  // No allowed gRPC services
        );
    }
    
    /**
     * Get the current configuration for testing purposes.
     */
    public PipelineClusterConfig getConfiguration(String clusterName) {
        return clusterConfigs.get(clusterName);
    }
    
    /**
     * Set configuration directly for testing purposes.
     */
    public void setConfiguration(String clusterName, PipelineClusterConfig config) {
        clusterConfigs.put(clusterName, config);
    }
}