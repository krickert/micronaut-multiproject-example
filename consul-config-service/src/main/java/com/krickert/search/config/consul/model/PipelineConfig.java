package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.runtime.context.scope.Refreshable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipeline configuration POJO.
 * This class represents the pipeline-specific configuration settings loaded from Consul.
 * It holds a map of all discovered pipeline configurations.
 * It is a singleton and is refreshable when configuration changes.
 */
@Singleton
@Refreshable // This bean will be recreated when a RefreshEvent occurs
@Getter
@Setter // Lombok setters for enabling map population by Micronaut config
@Serdeable
public class PipelineConfig {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineConfig.class);

    // Removed: activePipeline field - No longer tracking a single active pipeline here.
    // private String activePipeline;

    /**
     * Map of all pipeline configurations discovered and loaded from Consul, keyed by pipeline name.
     * Micronaut's configuration system populates this map based on keys matching 'pipeline.configs.*'.
     */
    private Map<String, PipelineConfigDto> pipelines = new HashMap<>();

    /**
     * Flag indicating whether the configuration has been initialized (e.g., after seeding or loading).
     * This helps differentiate between an empty config and an uninitialized state.
     * -- GETTER --
     *  Checks if the configuration has been marked as enabled (loaded).
     *<br/>
     * The setter returns true if the configuration is enabled, false otherwise.
     */
    private boolean enabled = false;

    private final ConsulKvService consulKvService;

    /**
     * Constructor with ConsulKvService.
     *
     * @param consulKvService the service for interacting with Consul KV store
     */
    @Inject
    public PipelineConfig(ConsulKvService consulKvService) {
        // We inject ConsulKvService but don't use it directly in this modified version
        // It might be used if we later add functionality to sync the *entire* pipeline map back to Consul.
        this.consulKvService = consulKvService;
        LOG.info("Creating PipelineConfig singleton bean.");
    }

    /**
     * Gets a specific pipeline configuration by its name.
     *
     * @param pipelineName the name of the pipeline
     * @return the pipeline configuration DTO, or null if a pipeline with that name is not found.
     */
    public PipelineConfigDto getPipeline(String pipelineName) {
        return pipelines.get(pipelineName);
    }
    /**
     * Adds or updates a pipeline configuration in the internal map.
     * Note: This primarily updates the in-memory representation.
     * Persisting changes back to Consul is handled by the ConfigController -> ConsulKvService flow.
     *
     * @param pipeline the pipeline configuration DTO to add or update.
     * @return a Mono indicating completion (currently simplified).
     */
    public Mono<Boolean> addOrUpdatePipeline(PipelineConfigDto pipeline) {
        String pipelineName = pipeline.getName();
        if (pipelineName == null || pipelineName.isBlank()) {
            LOG.error("Attempted to add or update a pipeline with a null or blank name.");
            return Mono.just(false); // Indicate failure
        }
        LOG.debug("Updating in-memory map for pipeline: {}", pipelineName);
        pipelines.put(pipelineName, pipeline);

        // Removed call to syncPipelineToConsul as it's simplified and
        // persistence is handled elsewhere (ConfigController).
        // If we needed to sync *other* aspects of the pipeline here, we would call a revised sync method.
        return Mono.just(true); // Indicate success (of updating the map)
    }

    // Removed: setActivePipeline() - This concept is removed.
    // public Mono<Boolean> setActivePipeline(String pipelineName) { ... }

    // Removed: syncPipelineToConsul() - Simplified as the active pipeline concept is gone
    // and service name setting should ideally happen during loading or DTO creation.
    // Persistence is handled by ConfigController.
    // private Mono<Boolean> syncPipelineToConsul(PipelineConfigDto pipeline) { ... }

    /**
     * Marks the configuration as enabled, typically after initial loading.
     */
    public void markAsEnabled() {
        this.enabled = true;
        LOG.info("PipelineConfig marked as enabled (configuration loaded).");
    }

    // Getter for the pipelines map is automatically provided by Lombok's @Getter
    // public Map<String, PipelineConfigDto> getPipelines() { return pipelines; }
}