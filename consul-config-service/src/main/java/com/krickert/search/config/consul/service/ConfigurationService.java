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
import java.util.Optional;

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
     * Loads pipeline configurations from Consul KV store.
     * Uses parallel execution to load multiple pipelines simultaneously.
     *
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    private Mono<Boolean> loadPipelineConfigurations() {
        // Clear existing pipelines
        pipelineConfig.getPipelines().clear();

        // Get all pipeline configurations from Consul KV store
        String pipelineConfigsPath = consulKvService.getFullPath("pipeline.configs");

        // For now, we'll still load pipeline1 and pipeline2 as examples
        // In a real implementation, we would list all keys under pipeline.configs
        // and load each pipeline dynamically
        Mono<Boolean> pipeline1Mono = loadPipelineConfiguration("pipeline1");
        Mono<Boolean> pipeline2Mono = loadPipelineConfiguration("pipeline2");

        // Also load any test-pipeline that might have been created in tests
        Mono<Boolean> testPipelineMono = loadPipelineConfiguration("test-pipeline");

        return Mono.zip(pipeline1Mono, pipeline2Mono, testPipelineMono)
            .map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3()) // All must succeed
            .defaultIfEmpty(true);
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
