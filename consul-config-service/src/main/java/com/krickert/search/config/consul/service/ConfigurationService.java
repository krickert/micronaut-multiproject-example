package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.event.ConfigChangeEvent;
import com.krickert.search.config.consul.model.ApplicationConfig;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.context.scope.Refreshable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for managing configuration.
 * This service loads configuration from Consul KV store and populates the configuration POJOs.
 * It also handles syncing changes back to Consul.
 */
@Singleton
@Refreshable
public class ConfigurationService implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConsulKvService consulKvService;
    private final ApplicationConfig applicationConfig;
    private final PipelineConfig pipelineConfig;
    private final String applicationName;

    /**
     * Constructor with dependencies.
     *
     * @param consulKvService the service for interacting with Consul KV store
     * @param applicationConfig the application configuration POJO
     * @param pipelineConfig the pipeline configuration POJO
     * @param applicationName the name of the application
     */
    public ConfigurationService(
            ConsulKvService consulKvService,
            ApplicationConfig applicationConfig,
            PipelineConfig pipelineConfig,
            @Value("${micronaut.application.name}") String applicationName) {
        this.consulKvService = consulKvService;
        this.applicationConfig = applicationConfig;
        this.pipelineConfig = pipelineConfig;
        this.applicationName = applicationName;
        LOG.info("ConfigurationService initialized for application: {}", applicationName);
    }

    /**
     * Handles the StartupEvent by loading configuration from Consul KV store.
     *
     * @param event the startup event
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("Loading configuration from Consul KV store");

        // Load application configuration
        loadApplicationConfig()
            .doOnSuccess(success -> {
                if (success) {
                    LOG.info("Application configuration loaded successfully");
                    applicationConfig.markAsEnabled();
                } else {
                    LOG.warn("Failed to load application configuration");
                }
            })
            .subscribe();

        // Load pipeline configuration
        loadPipelineConfig()
            .doOnSuccess(success -> {
                if (success) {
                    LOG.info("Pipeline configuration loaded successfully");
                    pipelineConfig.markAsEnabled();
                } else {
                    LOG.warn("Failed to load pipeline configuration");
                }
            })
            .subscribe();
    }

    /**
     * Handles ConfigChangeEvent by reloading the affected configuration.
     *
     * @param event the configuration change event
     */
    public void onConfigChange(ConfigChangeEvent event) {
        String keyPrefix = event.getKeyPrefix();
        LOG.info("Configuration change detected for key prefix: {}", keyPrefix);

        if (keyPrefix.startsWith(applicationName)) {
            // Reload application configuration
            loadApplicationConfig()
                .doOnSuccess(success -> {
                    if (success) {
                        LOG.info("Application configuration reloaded successfully");
                    } else {
                        LOG.warn("Failed to reload application configuration");
                    }
                })
                .subscribe();
        } else if (keyPrefix.startsWith("pipeline")) {
            // Reload pipeline configuration
            loadPipelineConfig()
                .doOnSuccess(success -> {
                    if (success) {
                        LOG.info("Pipeline configuration reloaded successfully");
                    } else {
                        LOG.warn("Failed to reload pipeline configuration");
                    }
                })
                .subscribe();
        }
    }

    /**
     * Loads application configuration from Consul KV store.
     *
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    private Mono<Boolean> loadApplicationConfig() {
        // Set the application name
        applicationConfig.setApplicationName(applicationName);

        // Load application configuration from Consul KV store
        // The path is ${micronaut.application.name}/config
        String configPath = applicationName + "/config";

        LOG.info("Loading application configuration from path: {}", configPath);

        // In a real-world scenario, you would load more configuration from Consul
        // For now, we'll just return true
        return Mono.just(true);
    }

    /**
     * Loads pipeline configuration from Consul KV store.
     *
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    private Mono<Boolean> loadPipelineConfig() {
        // Load active pipeline
        return consulKvService.getValue(consulKvService.getFullPath("pipeline.active"))
            .flatMap(activePipelineOpt -> {
                if (activePipelineOpt.isPresent()) {
                    String activePipeline = activePipelineOpt.get();
                    LOG.info("Active pipeline set to: {}", activePipeline);
                } else {
                    LOG.warn("No active pipeline found in Consul KV store");
                }

                // Load pipeline configurations
                return loadPipelineConfigurations();
            });
    }

    /**
     * Loads pipeline configurations from Consul KV store dynamically.
     * Uses parallel execution to load multiple pipelines simultaneously.
     *
     * @return a Mono that emits true if all discovered pipelines were loaded successfully, false otherwise
     */
    private Mono<Boolean> loadPipelineConfigurations() {
        // Clear existing pipelines
        pipelineConfig.getPipelines().clear();

        // Define the base path for pipeline configs
        String pipelineConfigsPrefix = consulKvService.getFullPath("pipeline.configs") + "/"; // Ensure trailing slash

        LOG.info("Dynamically loading pipeline configurations from prefix: {}", pipelineConfigsPrefix);

        return consulKvService.getKeysWithPrefix(pipelineConfigsPrefix)
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        LOG.warn("No pipeline configuration keys found under prefix: {}", pipelineConfigsPrefix);
                        return Mono.just(true); // No pipelines to load, operation is successful
                    }

                    // Extract unique pipeline names from the keys
                    // Example key: config/pipeline/pipeline.configs/my-pipeline/version
                    // We want to extract "my-pipeline"
                    Set<String> pipelineNames = keys.stream()
                            .map(key -> key.substring(pipelineConfigsPrefix.length())) // Remove prefix -> "my-pipeline/version"
                            .map(subKey -> subKey.split("/")[0]) // Get first part -> "my-pipeline"
                            .collect(Collectors.toSet());

                    LOG.info("Discovered pipeline names: {}", pipelineNames);

                    if (pipelineNames.isEmpty()) {
                        return Mono.just(true); // Should not happen if keys were found, but handle defensively
                    }

                    // Create a list of Monos, one for loading each discovered pipeline
                    List<Mono<Boolean>> loadMonos = pipelineNames.stream()
                            .map(this::loadPipelineConfiguration)
                            .collect(Collectors.toList());

                    // Use Mono.zip to execute all loads in parallel and combine results
                    // Ensure all operations return true
                    return Mono.zip(loadMonos, results -> {
                        for (Object result : results) {
                            if (!(Boolean) result) {
                                return false; // If any load failed, return false
                            }
                        }
                        return true; // All loads succeeded
                    });
                })
                .defaultIfEmpty(true) // If getKeysWithPrefix returns empty or error, consider it success (no pipelines loaded)
        .onErrorResume(e -> { // Handle potential errors during key fetching or processing
            LOG.error("Error loading dynamic pipeline configurations from prefix: {}", pipelineConfigsPrefix, e);
            return Mono.just(false); // Indicate failure
        });
    }

    /**
     * Loads a pipeline configuration from Consul KV store.
     *
     * @param pipelineName the name of the pipeline
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    private Mono<Boolean> loadPipelineConfiguration(String pipelineName) {
        PipelineConfigDto pipeline = new PipelineConfigDto(pipelineName);

        // Load pipeline version
        String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
        Mono<Optional<String>> versionMono = consulKvService.getValue(versionKey);

        // Load pipeline last updated timestamp
        String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");
        Mono<Optional<String>> lastUpdatedMono = consulKvService.getValue(lastUpdatedKey);

        // Combine the results
        return Mono.zip(versionMono, lastUpdatedMono)
            .flatMap(tuple -> {
                Optional<String> versionOpt = tuple.getT1();
                Optional<String> lastUpdatedOpt = tuple.getT2();

                // If either version or lastUpdated is missing, skip this pipeline
                if (versionOpt.isEmpty() || lastUpdatedOpt.isEmpty()) {
                    return Mono.just(true); // Skip this pipeline but don't fail
                }

                // Set the version and last updated timestamp
                try {
                    pipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                    pipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));
                } catch (Exception e) {
                    LOG.error("Error parsing pipeline version or last updated timestamp for pipeline: {}", pipelineName, e);
                    return Mono.just(false);
                }

                // Load services for the pipeline
                return loadPipelineServices(pipelineName, pipeline)
                    .flatMap(success -> {
                        if (success) {
                            pipelineConfig.getPipelines().put(pipelineName, pipeline);
                            LOG.info("Pipeline configuration loaded for: {}", pipelineName);
                            return Mono.just(true);
                        } else {
                            LOG.warn("Failed to load services for pipeline: {}", pipelineName);
                            return Mono.just(false);
                        }
                    });
            })
            .defaultIfEmpty(true); // If the pipeline doesn't exist, don't fail
    }

    /**
     * Loads services for a pipeline from Consul KV store.
     *
     * @param pipelineName the name of the pipeline
     * @param pipeline the pipeline configuration to populate
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    private Mono<Boolean> loadPipelineServices(String pipelineName, PipelineConfigDto pipeline) {
        // This is a simplified implementation. In a real-world scenario,
        // you would need to handle more complex loading logic.

        // For now, we'll just return true
        return Mono.just(true);
    }
}
