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

import java.time.LocalDateTime;
import java.util.*;

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

        if (pipeline == null) {
            // Try to load the pipeline from Consul
            String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
            Optional<String> versionOpt = consulKvService.getValue(versionKey).block();

            if (versionOpt != null && versionOpt.isPresent()) {
                // Pipeline exists in Consul, load it
                String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");
                Optional<String> lastUpdatedOpt = consulKvService.getValue(lastUpdatedKey).block();

                if (lastUpdatedOpt != null && lastUpdatedOpt.isPresent()) {
                    // Create a new pipeline with the version and last updated timestamp
                    pipeline = new PipelineConfigDto(pipelineName);
                    pipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                    pipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));

                    // Load services for this pipeline
                    loadServicesFromConsul(pipeline);

                    // Add the pipeline to the in-memory cache
                    pipelines.put(pipelineName, pipeline);
                    LOG.info("Loaded pipeline from Consul: {}", pipelineName);
                }
            }
        }

        return pipeline != null ? new PipelineConfigDto(pipeline) : null;
    }

    /**
     * Loads services for a pipeline from Consul KV store.
     *
     * @param pipeline the pipeline to load services for
     */
    public void loadServicesFromConsul(PipelineConfigDto pipeline) {
        String pipelineName = pipeline.getName();
        String servicesPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".service.");

        // Get all keys with the services prefix
        List<String> serviceKeys = consulKvService.getKeysWithPrefix(servicesPrefix).block();

        if (serviceKeys == null || serviceKeys.isEmpty()) {
            LOG.debug("No services found for pipeline: {}", pipelineName);
            return;
        }

        // Group keys by service name
        Map<String, List<String>> serviceKeyGroups = new HashMap<>();

        for (String key : serviceKeys) {
            // Extract service name from key
            // Format: prefix/pipeline.configs.{pipelineName}.service.{serviceName}.{property}
            String[] parts = key.split("\\.");
            if (parts.length < 6) {
                LOG.warn("Invalid service key format: {}", key);
                continue;
            }

            String serviceName = parts[parts.length - 2];
            serviceKeyGroups.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(key);
        }

        // Inside PipelineConfig.java -> loadServicesFromConsul method

        for (Map.Entry<String, List<String>> entry : serviceKeyGroups.entrySet()) {
            String serviceName = entry.getKey();
            List<String> keys = entry.getValue();

            ServiceConfigurationDto serviceConfig = new ServiceConfigurationDto();
            serviceConfig.setName(serviceName);

            // --- Temporary variables for JsonConfigOptions ---
            String loadedJsonConfigStr = null;
            String loadedJsonSchemaStr = null;

            // --- Temporary map for configParams ---
            Map<String, String> loadedConfigParams = new HashMap<>();


            // Load service properties (existing loop)
            for (String key : keys) {
                Optional<String> valueOpt = consulKvService.getValue(key).block();
                if (valueOpt == null || valueOpt.isEmpty()) {
                    continue;
                }

                String value = valueOpt.get();
                // Determine the actual property name relative to the service base key
                String fullPropertyName = key.startsWith(servicesPrefix) ? key.substring(servicesPrefix.length() + serviceName.length() + 1) : key;

                // Handle different property types based on the relative key structure
                if (fullPropertyName.startsWith("configParams.")) {
                    String paramName = fullPropertyName.substring("configParams.".length());
                    loadedConfigParams.put(paramName, value); // Store in temp map first
                } else if (fullPropertyName.startsWith("jsonConfig.")) { // Check for jsonConfig prefix
                    String jsonConfProp = fullPropertyName.substring("jsonConfig.".length());
                    if ("jsonConfig".equals(jsonConfProp)) {
                        loadedJsonConfigStr = value;
                        LOG.trace("Loaded jsonConfig string for {}: (string)", serviceName);
                    } else if ("jsonSchema".equals(jsonConfProp)) {
                        loadedJsonSchemaStr = value;
                        LOG.trace("Loaded jsonSchema string for {}: (string)", serviceName);
                    } else {
                        LOG.warn("Unknown pipeline service jsonConfig property key structure: {}", key);
                    }
                } else {
                    // Handle top-level properties
                    switch (fullPropertyName) {
                        case "name":
                            // Name already set from key group
                            break;
                        case "kafkaListenTopics":
                        case "kafka-listen-topics": // Handle potential legacy key name
                            serviceConfig.setKafkaListenTopics(Arrays.asList(value.split(",")));
                            break;
                        case "kafkaPublishTopics":
                        case "kafka-publish-topics": // Handle potential legacy key name
                            serviceConfig.setKafkaPublishTopics(Arrays.asList(value.split(",")));
                            break;
                        case "grpcForwardTo":
                        case "grpc-forward-to": // Handle potential legacy key name
                            serviceConfig.setGrpcForwardTo(Arrays.asList(value.split(",")));
                            break;
                        case "serviceImplementation":
                            serviceConfig.setServiceImplementation(value);
                            break;
                        default:
                            LOG.warn("Unknown top-level pipeline service property key structure: {}", key);
                            break;
                    }
                }
            } // End loop through keys for one service

            // --- Set configParams if any were found ---
            if (!loadedConfigParams.isEmpty()) {
                serviceConfig.setConfigParams(loadedConfigParams);
            }

            // --- >>> RECONSTRUCT JsonConfigOptions if data was loaded <<< ---
            if (loadedJsonConfigStr != null || loadedJsonSchemaStr != null) {
                // Create new JsonConfigOptions only if we found data for it
                JsonConfigOptions jsonOptions = new JsonConfigOptions(
                        loadedJsonConfigStr != null ? loadedJsonConfigStr : "{}", // Default if null
                        loadedJsonSchemaStr != null ? loadedJsonSchemaStr : "{}"  // Default if null
                );
                serviceConfig.setJsonConfig(jsonOptions); // Set on the DTO
                LOG.debug("Reconstructed JsonConfigOptions for service {}", serviceName);
            }
            // --- >>> END RECONSTRUCT <<< ---

            // Add the fully loaded service to the pipeline
            pipeline.addOrUpdateService(serviceConfig);
            LOG.debug("Loaded service {} for pipeline {}", serviceName, pipelineName);
        } // End of service group loop
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

            // Increment the version for existing pipelines
            pipeline.incrementVersion();
        } else {
            // For new pipelines, ensure the version is set to 1
            if (pipeline.getPipelineVersion() == 0) {
                pipeline.setPipelineVersion(1);
                pipeline.setPipelineLastUpdated(LocalDateTime.now());
            }
        }

        // Update the pipeline in the map
        pipelines.put(pipelineName, pipeline);

        // Sync with Consul
        return syncPipelineToConsul(pipeline);
    }


    /**
     * Syncs a pipeline configuration to Consul KV store using Compare-And-Swap (CAS) to ensure
     * atomic updates and prevent concurrent modification issues.
     *
     * @param pipeline the pipeline configuration to sync
     * @return a Mono that completes with true if the operation was successful, false otherwise
     */
    private Mono<Boolean> syncPipelineToConsul(PipelineConfigDto pipeline) {
        String pipelineName = pipeline.getName();
        String versionKey = "pipeline.configs." + pipelineName + ".version";
        String versionKeyFullPath = consulKvService.getFullPath(versionKey);

        // First, get the current ModifyIndex of the version key
        return consulKvService.getModifyIndex(versionKeyFullPath)
        .flatMap(modifyIndex -> {
            LOG.debug("Current ModifyIndex for pipeline '{}' version key: {}", pipelineName, modifyIndex);

            // Prepare a map to hold all key-value pairs to be updated (except version and lastUpdated)
            Map<String, String> otherKeysMap = new HashMap<>();

            // Sync pipeline services
            for (Map.Entry<String, ServiceConfigurationDto> entry : pipeline.getServices().entrySet()) {
                String serviceName = entry.getKey();
                ServiceConfigurationDto serviceConfig = entry.getValue();

                // Set the service name if not already set
                if (serviceConfig.getName() == null) {
                    serviceConfig.setName(serviceName);
                }

                // Create keys for service configuration
                String serviceBaseKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".services." + serviceName);
                String serviceNameKey = serviceBaseKey + ".name";
                String serviceListenTopicsKey = serviceBaseKey + ".kafkaListenTopics";
                String servicePublishTopicsKey = serviceBaseKey + ".kafkaPublishTopics";
                String serviceGrpcForwardToKey = serviceBaseKey + ".grpcForwardTo";
                String serviceImplementationKey = serviceBaseKey + ".serviceImplementation";

                // Add service configuration to the key-value map
                otherKeysMap.put(serviceNameKey, serviceConfig.getName());

                // Handle lists by converting them to comma-separated strings
                if (serviceConfig.getKafkaListenTopics() != null && !serviceConfig.getKafkaListenTopics().isEmpty()) {
                    otherKeysMap.put(serviceListenTopicsKey, String.join(",", serviceConfig.getKafkaListenTopics()));
                }

                if (serviceConfig.getKafkaPublishTopics() != null && !serviceConfig.getKafkaPublishTopics().isEmpty()) {
                    otherKeysMap.put(servicePublishTopicsKey, String.join(",", serviceConfig.getKafkaPublishTopics()));
                }

                if (serviceConfig.getGrpcForwardTo() != null && !serviceConfig.getGrpcForwardTo().isEmpty()) {
                    otherKeysMap.put(serviceGrpcForwardToKey, String.join(",", serviceConfig.getGrpcForwardTo()));
                }

                if (serviceConfig.getServiceImplementation() != null) {
                    otherKeysMap.put(serviceImplementationKey, serviceConfig.getServiceImplementation());
                }

                // Handle config params if present
                if (serviceConfig.getConfigParams() != null && !serviceConfig.getConfigParams().isEmpty()) {
                    for (Map.Entry<String, String> paramEntry : serviceConfig.getConfigParams().entrySet()) {
                        String paramKey = serviceBaseKey + ".configParams." + paramEntry.getKey();
                        otherKeysMap.put(paramKey, paramEntry.getValue());
                    }
                }

                // ... after handling configParams ...
                // --- >>> ADD JSON CONFIG PERSISTENCE <<< ---
                if (serviceConfig.getJsonConfig() != null) {
                    JsonConfigOptions jsonOptions = serviceConfig.getJsonConfig();
                    // Define keys using the same base path + ".jsonConfig." + field name
                    String jsonConfigKey = serviceBaseKey + ".jsonConfig.jsonConfig"; // Note: Using '.' separator
                    String jsonSchemaKey = serviceBaseKey + ".jsonConfig.jsonSchema";

                    // Add the values to the map if they are not null
                    if (jsonOptions.getJsonConfig() != null) {
                        otherKeysMap.put(jsonConfigKey, jsonOptions.getJsonConfig());
                        LOG.trace("Preparing to save {} = (jsonConfig string)", jsonConfigKey); // Avoid logging potentially large strings
                    }
                    if (jsonOptions.getJsonSchema() != null) {
                        otherKeysMap.put(jsonSchemaKey, jsonOptions.getJsonSchema());
                        LOG.trace("Preparing to save {} = (jsonSchema string)", jsonSchemaKey); // Avoid logging potentially large strings
                    }
                }
                // --- >>> END JSON CONFIG PERSISTENCE <<< ---
            }

            // Use CAS to update the pipeline atomically
            LOG.debug("Attempting CAS update for pipeline '{}' with ModifyIndex {}", pipelineName, modifyIndex);
            return consulKvService.savePipelineUpdateWithCas(
                    pipelineName,
                    pipeline.getPipelineVersion(),
                    pipeline.getPipelineLastUpdated(),
                    modifyIndex,
                    otherKeysMap
            )
            .map(success -> {
                if (success) {
                    LOG.info("Successfully synced pipeline configuration to Consul using CAS: {}", pipelineName);
                    return true;
                } else {
                    LOG.error("Failed to sync pipeline configuration to Consul using CAS: {}", pipelineName);
                    return false;
                }
            })
            .onErrorResume(e -> {
                LOG.error("Error syncing pipeline configuration to Consul using CAS: {}", pipelineName, e);
                return Mono.just(false);
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

    /**
     * Resets the state of this PipelineConfig instance.
     * This is primarily used for testing to ensure a clean state between tests.
     */
    public void reset() {
        this.pipelines.clear();
        this.enabled = false;
        LOG.info("PipelineConfig state has been reset");
    }
}
