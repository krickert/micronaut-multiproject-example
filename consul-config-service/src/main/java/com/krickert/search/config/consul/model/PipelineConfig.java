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
    // Inside PipelineConfig.java -> loadServicesFromConsul method
    public void loadServicesFromConsul(PipelineConfigDto pipeline) {
        String pipelineName = pipeline.getName();
        // Corrected prefix from previous step
        String servicesPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".services.");

        List<String> serviceKeys = consulKvService.getKeysWithPrefix(servicesPrefix).block();

        if (serviceKeys == null || serviceKeys.isEmpty()) {
            LOG.debug("No services found for pipeline: {}", pipelineName);
            return;
        }

        Map<String, ServiceConfigurationDto> loadedServices = new HashMap<>();

        for (String key : serviceKeys) {
            // Get the service name part (e.g., "myCustomServiceInstance")
            String keyRelativeToPipeline = key.substring(consulKvService.getFullPath("pipeline.configs." + pipelineName + ".services.").length());
            String[] keyParts = keyRelativeToPipeline.split("\\.", 2); // Split only on the first dot after service name
            if (keyParts.length < 2) {
                LOG.warn("Cannot determine service name or property from key: {}", key);
                continue;
            }
            String serviceName = keyParts[0];
            String propertyPath = keyParts[1]; // e.g., "name", "configParams.timeout", "jsonConfig.jsonConfig"

            // Get or create the DTO for this service
            ServiceConfigurationDto serviceConfig = loadedServices.computeIfAbsent(serviceName, name -> {
                ServiceConfigurationDto dto = new ServiceConfigurationDto();
                dto.setName(name);
                return dto;
            });

            Optional<String> valueOpt = consulKvService.getValue(key).block();
            if (valueOpt.isEmpty()) {
                continue;
            }
            String value = valueOpt.get();

            // --- NEW: Handle properties based on path ---
            if (propertyPath.equals("name")) {
                // Already set when creating/getting DTO
            } else if (propertyPath.equals("serviceImplementation")) {
                serviceConfig.setServiceImplementation(value);
            } else if (propertyPath.equals("kafkaListenTopics") || propertyPath.equals("kafka-listen-topics")) {
                serviceConfig.setKafkaListenTopics(Arrays.asList(value.split(",")));
            } else if (propertyPath.equals("kafkaPublishTopics") || propertyPath.equals("kafka-publish-topics")) {
                serviceConfig.setKafkaPublishTopics(Arrays.asList(value.split(",")));
            } else if (propertyPath.equals("grpcForwardTo") || propertyPath.equals("grpc-forward-to")) {
                serviceConfig.setGrpcForwardTo(Arrays.asList(value.split(",")));
            } else if (propertyPath.startsWith("configParams.")) {
                String paramName = propertyPath.substring("configParams.".length());
                if (serviceConfig.getConfigParams() == null) {
                    serviceConfig.setConfigParams(new HashMap<>());
                }
                serviceConfig.getConfigParams().put(paramName, value);
            } else if (propertyPath.startsWith("jsonConfig.")) {
                // Ensure JsonConfigOptions object exists
                if (serviceConfig.getJsonConfig() == null) {
                    serviceConfig.setJsonConfig(new JsonConfigOptions("{}", "{}")); // Initialize with defaults
                }
                String jsonConfProp = propertyPath.substring("jsonConfig.".length());
                if ("jsonConfig".equals(jsonConfProp)) {
                    serviceConfig.getJsonConfig().setJsonConfig(value);
                    LOG.trace("Loaded jsonConfig string for {}: (string)", serviceName);
                } else if ("jsonSchema".equals(jsonConfProp)) {
                    serviceConfig.getJsonConfig().setJsonSchema(value);
                    LOG.trace("Loaded jsonSchema string for {}: (string)", serviceName);
                } else {
                    LOG.warn("Unknown pipeline service jsonConfig property key structure: {}", key);
                }
            } else {
                LOG.warn("Unknown pipeline service property key structure: {}", key);
            }
            // --- END NEW HANDLING ---

        } // End loop through all service keys

        // Add all fully loaded services to the pipeline DTO
        pipeline.setServices(loadedServices);
        if (!loadedServices.isEmpty()) {
            LOG.debug("Loaded {} services for pipeline {}", loadedServices.size(), pipelineName);
        }
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
