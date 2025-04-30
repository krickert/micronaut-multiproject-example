package com.krickert.search.config.consul.model;

import com.krickert.search.config.consul.exception.PipelineVersionConflictException;
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
 * This class represents the pipeline-specific configuration settings.
 * It is a singleton and is refreshable when configuration changes.
 */
@Singleton
@Refreshable
@Getter
@Setter
@Serdeable
public class PipelineConfig {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineConfig.class);

    /**
     * The active pipeline name.
     */
    private String activePipeline;

    /**
     * Map of pipeline configurations, keyed by pipeline name.
     */
    private Map<String, PipelineConfigDto> pipelines = new HashMap<>();

    /**
     * Flag indicating whether the configuration has been initialized.
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
        this.consulKvService = consulKvService;
        LOG.info("Creating PipelineConfig singleton");
    }

    /**
     * Gets a pipeline configuration by name.
     * Returns a deep copy of the pipeline to prevent concurrent modification issues.
     *
     * @param pipelineName the name of the pipeline
     * @return a deep copy of the pipeline configuration, or null if not found
     */
    public PipelineConfigDto getPipeline(String pipelineName) {
        PipelineConfigDto pipeline = pipelines.get(pipelineName);
        return pipeline != null ? new PipelineConfigDto(pipeline) : null;
    }

    /**
     * Gets the active pipeline configuration.
     * Returns a deep copy of the pipeline to prevent concurrent modification issues.
     *
     * @return a deep copy of the active pipeline configuration, or null if not set
     */
    public PipelineConfigDto getActivePipeline() {
        return activePipeline != null ? getPipeline(activePipeline) : null;
    }

    /**
     * Adds or updates a pipeline configuration.
     *
     * @param pipeline the pipeline configuration to add or update
     * @return a Mono that completes when the operation is done
     * @throws PipelineVersionConflictException if the pipeline has been updated since it was loaded
     */
    public Mono<Boolean> addOrUpdatePipeline(PipelineConfigDto pipeline) {
        String pipelineName = pipeline.getName();

        // Check if the pipeline already exists
        PipelineConfigDto existingPipeline = pipelines.get(pipelineName);
        if (existingPipeline != null) {
            // Check if the versions match
            if (existingPipeline.getPipelineVersion() != pipeline.getPipelineVersion()) {
                // Versions don't match, throw an exception
                throw new PipelineVersionConflictException(
                    pipelineName,
                    pipeline.getPipelineVersion(),
                    existingPipeline.getPipelineVersion(),
                    existingPipeline.getPipelineLastUpdated()
                );
            }
        }

        // Always increment the version and update the timestamp when saving
        pipeline.incrementVersion();

        // Update the pipeline in the map
        pipelines.put(pipelineName, pipeline);

        // Sync with Consul
        return syncPipelineToConsul(pipeline);
    }

    /**
     * Sets the active pipeline.
     *
     * @param pipelineName the name of the pipeline to set as active
     * @return a Mono that completes when the operation is done
     */
    public Mono<Boolean> setActivePipeline(String pipelineName) {
        if (!pipelines.containsKey(pipelineName)) {
            LOG.error("Cannot set active pipeline to non-existent pipeline: {}", pipelineName);
            return Mono.just(false);
        }

        this.activePipeline = pipelineName;

        // Sync with Consul
        return consulKvService.putValue(
                consulKvService.getFullPath("pipeline.active"), 
                pipelineName);
    }

    /**
     * Syncs a pipeline configuration to Consul KV store.
     *
     * @param pipeline the pipeline configuration to sync
     * @return a Mono that completes when the operation is done
     */
    private Mono<Boolean> syncPipelineToConsul(PipelineConfigDto pipeline) {
        // This implementation uses a more atomic approach by batching operations
        String pipelineName = pipeline.getName();

        // For each service in the pipeline, sync its configuration
        return Mono.just(true)
                .flatMap(success -> {
                    // Sync pipeline services
                    for (Map.Entry<String, ServiceConfigurationDto> entry : pipeline.getServices().entrySet()) {
                        String serviceName = entry.getKey();
                        ServiceConfigurationDto serviceConfig = entry.getValue();

                        // Set the service name if not already set
                        if (serviceConfig.getName() == null) {
                            serviceConfig.setName(serviceName);
                        }
                    }

                    // Sync pipeline version and last updated timestamp
                    String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
                    String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");

                    // Use a more atomic approach by batching the operations
                    // First, prepare the values to be updated
                    String versionValue = String.valueOf(pipeline.getPipelineVersion());
                    String lastUpdatedValue = pipeline.getPipelineLastUpdated().toString();

                    // Then, update both values in a single flatMap chain
                    return consulKvService.putValue(versionKey, versionValue)
                            .flatMap(versionSuccess -> {
                                if (!versionSuccess) {
                                    LOG.error("Failed to update version for pipeline: {}", pipelineName);
                                    return Mono.just(false);
                                }
                                return consulKvService.putValue(lastUpdatedKey, lastUpdatedValue)
                                        .map(lastUpdatedSuccess -> {
                                            if (!lastUpdatedSuccess) {
                                                LOG.error("Failed to update last updated timestamp for pipeline: {}", pipelineName);
                                                // If the last updated timestamp update fails, we should try to revert the version update
                                                // to maintain consistency, but this is a best-effort approach
                                                consulKvService.putValue(versionKey, String.valueOf(pipeline.getPipelineVersion() - 1))
                                                        .subscribe(
                                                                revertSuccess -> {
                                                                    if (!revertSuccess) {
                                                                        LOG.error("Failed to revert version update for pipeline: {}", pipelineName);
                                                                    }
                                                                },
                                                                error -> LOG.error("Error reverting version update for pipeline: {}", pipelineName, error)
                                                        );
                                                return false;
                                            }
                                            return true;
                                        });
                            });
                });
    }

    /**
     * Sets the enabled flag to true.
     * This method is called after the configuration has been seeded.
     */
    public void markAsEnabled() {
        this.enabled = true;
        LOG.info("PipelineConfig marked as enabled");
    }

    /**
     * Checks if the configuration is enabled.
     *
     * @return true if the configuration is enabled, false otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }
}
