package com.krickert.search.engine.bootstrap.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import com.krickert.search.engine.bootstrap.BootstrapService;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of BootstrapService that uses Consul for configuration storage.
 */
@Singleton
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class BootstrapServiceImpl implements BootstrapService {
    
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapServiceImpl.class);
    private static final String PIPELINE_CONFIG_KEY_PREFIX = "pipeline-configs/clusters/";
    
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Boolean> bootstrappedClusters = new ConcurrentHashMap<>();
    
    @Inject
    public BootstrapServiceImpl(ConsulKvService consulKvService, ObjectMapper objectMapper) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Mono<Void> bootstrap(String clusterName) {
        return Mono.defer(() -> {
            LOG.info("Starting bootstrap process for cluster: {}", clusterName);
            
            // Check if already bootstrapped
            if (bootstrappedClusters.containsKey(clusterName)) {
                LOG.info("Cluster {} is already bootstrapped", clusterName);
                return Mono.empty();
            }
            
            return ensureClusterConfiguration(clusterName)
                    .then(registerDefaultModules(clusterName))
                    .doOnSuccess(v -> {
                        bootstrappedClusters.put(clusterName, true);
                        LOG.info("Bootstrap completed successfully for cluster: {}", clusterName);
                    })
                    .doOnError(error -> {
                        LOG.error("Bootstrap failed for cluster {}: {}", clusterName, error.getMessage(), error);
                    });
        });
    }
    
    @Override
    public Mono<PipelineClusterConfig> ensureClusterConfiguration(String clusterName) {
        String configKey = PIPELINE_CONFIG_KEY_PREFIX + clusterName;
        
        return consulKvService.getValue(configKey)
                .flatMap(optionalValue -> {
                    if (optionalValue.isPresent()) {
                        try {
                            PipelineClusterConfig config = objectMapper.readValue(
                                    optionalValue.get(), PipelineClusterConfig.class);
                            LOG.info("Cluster configuration already exists for: {}", clusterName);
                            return Mono.just(config);
                        } catch (Exception e) {
                            LOG.error("Failed to parse existing configuration for cluster: {}", clusterName, e);
                            return createAndStoreMinimalConfig(clusterName);
                        }
                    } else {
                        return createAndStoreMinimalConfig(clusterName);
                    }
                });
    }
    
    private Mono<PipelineClusterConfig> createAndStoreMinimalConfig(String clusterName) {
        LOG.info("Creating minimal cluster configuration for: {}", clusterName);
        PipelineClusterConfig minimalConfig = createMinimalClusterConfig(clusterName);
        
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(minimalConfig))
                .flatMap(configJson -> {
                    String configKey = PIPELINE_CONFIG_KEY_PREFIX + clusterName;
                    return consulKvService.putValue(configKey, configJson)
                            .map(success -> {
                                if (success) {
                                    LOG.info("Stored minimal configuration for cluster: {}", clusterName);
                                } else {
                                    LOG.error("Failed to store configuration for cluster: {}", clusterName);
                                }
                                return minimalConfig;
                            });
                })
                .onErrorReturn(minimalConfig);
    }
    
    @Override
    public Mono<Void> registerDefaultModules(String clusterName) {
        return Mono.fromRunnable(() -> {
            LOG.info("Registering default modules for cluster: {}", clusterName);
            // Default module registration will be handled by the module registration service
            // This is a placeholder for now - modules will self-register or be registered via CLI
        });
    }
    
    @Override
    public Mono<Boolean> isBootstrapped(String clusterName) {
        return Mono.fromCallable(() -> bootstrappedClusters.containsKey(clusterName));
    }
    
    @Override
    public Mono<Void> resetBootstrap(String clusterName) {
        return Mono.fromRunnable(() -> {
            LOG.warn("Resetting bootstrap state for cluster: {}", clusterName);
            bootstrappedClusters.remove(clusterName);
        });
    }
    
    private PipelineClusterConfig createMinimalClusterConfig(String clusterName) {
        return new PipelineClusterConfig(
                clusterName,
                new PipelineGraphConfig(Collections.emptyMap()), // Empty map of pipelines
                new PipelineModuleMap(Collections.emptyMap()),  // Empty map of modules
                null, // No default pipeline
                Collections.emptySet(), // No allowed Kafka topics yet
                Collections.emptySet()  // No allowed gRPC services yet
        );
    }
}