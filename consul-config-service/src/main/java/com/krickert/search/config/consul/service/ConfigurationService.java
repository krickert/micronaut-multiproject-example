package com.krickert.search.config.consul.service;

import com.krickert.search.config.consul.event.ConfigChangeEvent;
import com.krickert.search.config.consul.model.*;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.context.scope.Refreshable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
        LOG.info("Loading configuration from Consul KV store on StartupEvent");
        // Consider removing this listener if Seeder triggers refresh, to avoid race conditions
        loadConfiguration();
    }

    public void onConfigChange(ConfigChangeEvent event) {
        LOG.info("Configuration change detected for key prefix: {}. Reloading.", event.getKeyPrefix());
        loadConfiguration(); // Reload all on any relevant change
    }

    // Central method to load all configs
    private void loadConfiguration() {
        LOG.debug("Starting full configuration load.");
        // Run sequentially or parallel? Let's run sequentially for now.
        loadApplicationConfig()
            .flatMap(appSuccess -> loadPipelineConfig()) // Load pipelines after app config
            .subscribe(
                success -> LOG.info("Full configuration load attempt completed. Success: {}", success),
                error -> LOG.error("Error during full configuration load", error)
            );
    }


    private Mono<Boolean> loadApplicationConfig() {
        applicationConfig.setApplicationName(applicationName);
        String configPath = applicationName + "/config";
        LOG.info("Loading application configuration from path: {}", configPath);
        // Actual loading logic would go here...
        // For now, just mark as enabled
        applicationConfig.markAsEnabled();
        LOG.info("Application configuration marked as enabled (loading simulated).");
        return Mono.just(true);
    }

    private Mono<Boolean> loadPipelineConfig() {
        pipelineConfig.getPipelines().clear(); // Clear before loading
        pipelineConfig.setEnabled(false); // Mark as not enabled until load succeeds

        String pipelineConfigsPrefix = consulKvService.getFullPath("pipeline.configs") + "/";
        LOG.info("Loading pipeline configurations from prefix: {}", pipelineConfigsPrefix);

        return loadPipelineConfigurations(pipelineConfigsPrefix)
            .doOnSuccess(success -> {
                if (success) {
                    LOG.info("Pipeline configuration loaded successfully.");
                    pipelineConfig.markAsEnabled(); // Mark enabled only if loading succeeded
                } else {
                    LOG.warn("Failed to load pipeline configuration fully.");
                    // Keep pipelineConfig disabled
                }
            })
            .onErrorResume(e -> {
                 LOG.error("Error during pipeline configuration loading", e);
                 return Mono.just(false); // Ensure failure is signalled
            });
    }

    /**
     * Loads pipeline configurations from Consul KV store dynamically.
     *
     * @param pipelineConfigsPrefix The prefix for pipeline configs in Consul.
     * @return a Mono that emits true if ALL discovered pipelines were loaded successfully, false otherwise.
     */
    private Mono<Boolean> loadPipelineConfigurations(String pipelineConfigsPrefix) {

        return consulKvService.getKeysWithPrefix(pipelineConfigsPrefix)
                .flatMap(keys -> {
                    if (keys.isEmpty()) {
                        LOG.warn("No pipeline configuration keys found under prefix: {}", pipelineConfigsPrefix);
                        return Mono.just(true); // No pipelines is considered a success
                    }

                    // --- *** CORRECTED NAME DISCOVERY *** ---
                    Set<String> pipelineNames = keys.stream()
                            .filter(key -> key.startsWith(pipelineConfigsPrefix) && key.length() > pipelineConfigsPrefix.length())
                            .map(key -> key.substring(pipelineConfigsPrefix.length()))
                            .map(subKey -> {
                                int firstSlash = subKey.indexOf('/');
                                return (firstSlash > 0) ? subKey.substring(0, firstSlash) : subKey;
                            })
                            .filter(name -> !name.contains("/")) // Ensure it's just the name part
                            .collect(Collectors.toSet());
                    // --- *** END CORRECTION *** ---

                    LOG.info("Correctly discovered pipeline names: {}", pipelineNames);

                    if (pipelineNames.isEmpty()) {
                        LOG.warn("No valid pipeline names extracted from keys under prefix: {}", pipelineConfigsPrefix);
                        return Mono.just(true); // No valid pipelines is considered success
                    }

                    // Load each discovered pipeline configuration
                    List<Mono<Boolean>> loadMonos = pipelineNames.stream()
                            .map(this::loadPipelineConfiguration) // Use method reference
                            .collect(Collectors.toList());

                    // Use Mono.zip to wait for all pipelines to load attempt
                    // Return true only if ALL attempts succeeded (or skipped gracefully)
                    return Mono.zip(loadMonos, results -> {
                        boolean allSucceeded = true;
                        for (Object result : results) {
                            if (!(Boolean) result) {
                                allSucceeded = false;
                                break; // If one failed, the overall result is false
                            }
                        }
                        LOG.info("Result of loading all discovered pipelines. All succeeded: {}", allSucceeded);
                        return allSucceeded;
                    });
                })
                .defaultIfEmpty(true) // If getKeysWithPrefix is empty or returns empty list after filtering.
                .onErrorResume(e -> {
                    LOG.error("Error loading dynamic pipeline configurations from prefix: {}", pipelineConfigsPrefix, e);
                    return Mono.just(false);
                });
    }

    /**
     * Loads a specific pipeline configuration, requiring version and lastUpdated.
     *
     * @param pipelineName the name of the pipeline
     * @return a Mono emitting true if loaded successfully, false if required keys are missing or load fails.
     */
    private Mono<Boolean> loadPipelineConfiguration(String pipelineName) {
        LOG.debug("Attempting to load configuration for pipeline: {}", pipelineName);
        PipelineConfigDto pipeline = new PipelineConfigDto(pipelineName);

        String versionKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".version");
        String lastUpdatedKey = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".lastUpdated");

        Mono<Optional<String>> versionMono = consulKvService.getValue(versionKey).defaultIfEmpty(Optional.empty());
        Mono<Optional<String>> lastUpdatedMono = consulKvService.getValue(lastUpdatedKey).defaultIfEmpty(Optional.empty());

        return Mono.zip(versionMono, lastUpdatedMono)
            .flatMap(tuple -> {
                Optional<String> versionOpt = tuple.getT1();
                Optional<String> lastUpdatedOpt = tuple.getT2();

                // --- Strict Check: Require BOTH version and lastUpdated ---
                if (versionOpt.isEmpty() || lastUpdatedOpt.isEmpty()) {
                    LOG.warn("Skipping load for pipeline '{}' because required key '{}' or '{}' is missing in Consul.",
                             pipelineName, versionKey, lastUpdatedKey);
                    return Mono.just(false); // Indicate failure for THIS pipeline load
                }

                try {
                    pipeline.setPipelineVersion(Long.parseLong(versionOpt.get()));
                    pipeline.setPipelineLastUpdated(LocalDateTime.parse(lastUpdatedOpt.get()));
                     LOG.debug("Successfully parsed metadata for pipeline '{}'", pipelineName);
                } catch (Exception e) {
                    LOG.error("Error parsing required metadata (version/lastUpdated) for pipeline: {}. Skipping.", pipelineName, e);
                    return Mono.just(false); // Indicate failure for THIS pipeline load
                }

                // --- Proceed to load services ONLY if metadata was valid ---
                return loadPipelineServices(pipelineName, pipeline)
                    .flatMap(servicesLoadedSuccess -> {
                        if (servicesLoadedSuccess) {
                            pipelineConfig.getPipelines().put(pipelineName, pipeline);
                            LOG.info("Pipeline configuration successfully loaded for: {}", pipelineName);
                            return Mono.just(true); // Success for this pipeline
                        } else {
                            LOG.warn("Failed to load services for pipeline: {}. Pipeline load failed.", pipelineName);
                            return Mono.just(false); // Failure for this pipeline
                        }
                    });
            })
            .defaultIfEmpty(false) // If version/lastUpdated don't exist at all, treat as failure for this pipeline
            .onErrorResume(e -> {
                 LOG.error("Error loading configuration for pipeline: {}", pipelineName, e);
                 return Mono.just(false); // Failure on error
            });
    }

    /**
     * Loads services for a pipeline from Consul KV store.
     * 
     * @param pipelineName the name of the pipeline
     * @param pipeline the pipeline configuration to populate with services
     * @return a Mono that emits true if all services were loaded successfully, false otherwise
     */
    private Mono<Boolean> loadPipelineServices(String pipelineName, PipelineConfigDto pipeline) {
        LOG.debug("Loading services for pipeline: {}", pipelineName);

        String serviceConfigPrefix = consulKvService.getFullPath("pipeline.configs." + pipelineName + ".service");

        return consulKvService.getKeysWithPrefix(serviceConfigPrefix)
            .flatMap(keys -> {
                if (keys.isEmpty()) {
                    LOG.warn("No service configuration keys found for pipeline: {}", pipelineName);
                    return Mono.just(true); // No services is considered a success
                }

                // Extract service names from keys
                Set<String> serviceNames = keys.stream()
                    .filter(key -> key.startsWith(serviceConfigPrefix))
                    .map(key -> {
                        // Extract service name from key path
                        String relativePath = key.substring(serviceConfigPrefix.length());
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }
                        int nextSlash = relativePath.indexOf('/');
                        return (nextSlash > 0) ? relativePath.substring(0, nextSlash) : relativePath;
                    })
                    .filter(name -> !name.isEmpty() && !name.contains("/"))
                    .collect(Collectors.toSet());

                LOG.info("Discovered service names for pipeline {}: {}", pipelineName, serviceNames);

                if (serviceNames.isEmpty()) {
                    LOG.warn("No valid service names extracted from keys for pipeline: {}", pipelineName);
                    return Mono.just(true); // No valid services is considered success
                }

                // Load each discovered service configuration
                List<Mono<Boolean>> loadMonos = serviceNames.stream()
                    .map(serviceName -> loadServiceConfiguration(pipelineName, serviceName, pipeline))
                    .collect(Collectors.toList());

                // Use Mono.zip to wait for all services to load
                return Mono.zip(loadMonos, results -> {
                    boolean allSucceeded = true;
                    for (Object result : results) {
                        if (!(Boolean) result) {
                            allSucceeded = false;
                            break;
                        }
                    }
                    LOG.info("Result of loading all services for pipeline {}. All succeeded: {}", pipelineName, allSucceeded);
                    return allSucceeded;
                });
            })
            .defaultIfEmpty(true)
            .onErrorResume(e -> {
                LOG.error("Error loading services for pipeline: {}", pipelineName, e);
                return Mono.just(false);
            });
    }

    /**
     * Loads configuration for a specific service in a pipeline.
     * 
     * @param pipelineName the name of the pipeline
     * @param serviceName the name of the service
     * @param pipeline the pipeline configuration to add the service to
     * @return a Mono that emits true if the service was loaded successfully, false otherwise
     */
    private Mono<Boolean> loadServiceConfiguration(String pipelineName, String serviceName, PipelineConfigDto pipeline) {
        LOG.debug("Loading configuration for service {} in pipeline {}", serviceName, pipelineName);

        ServiceConfigurationDto serviceConfig = new ServiceConfigurationDto();
        serviceConfig.setName(serviceName);

        String baseKeyPrefix = "pipeline.configs." + pipelineName + ".service." + serviceName;

        // Keys to load
        String kafkaListenTopicsKey = consulKvService.getFullPath(baseKeyPrefix + ".kafka-listen-topics");
        String kafkaPublishTopicsKey = consulKvService.getFullPath(baseKeyPrefix + ".kafka-publish-topics");
        String grpcForwardToKey = consulKvService.getFullPath(baseKeyPrefix + ".grpc-forward-to");
        String serviceImplKey = consulKvService.getFullPath(baseKeyPrefix + ".service-implementation");
        String configParamsPrefix = consulKvService.getFullPath(baseKeyPrefix + ".config-params");
        String jsonConfigKey = consulKvService.getFullPath(baseKeyPrefix + ".json-config");
        String jsonSchemaKey = consulKvService.getFullPath(baseKeyPrefix + ".json-schema");

        // Load kafka listen topics
        Mono<Optional<String>> kafkaListenTopicsMono = consulKvService.getValue(kafkaListenTopicsKey)
            .defaultIfEmpty(Optional.empty());

        // Load kafka publish topics
        Mono<Optional<String>> kafkaPublishTopicsMono = consulKvService.getValue(kafkaPublishTopicsKey)
            .defaultIfEmpty(Optional.empty());

        // Load grpc forward to
        Mono<Optional<String>> grpcForwardToMono = consulKvService.getValue(grpcForwardToKey)
            .defaultIfEmpty(Optional.empty());

        // Load service implementation
        Mono<Optional<String>> serviceImplMono = consulKvService.getValue(serviceImplKey)
            .defaultIfEmpty(Optional.empty());

        // Load JSON configuration
        Mono<Optional<String>> jsonConfigMono = consulKvService.getValue(jsonConfigKey)
            .defaultIfEmpty(Optional.empty());

        // Load JSON schema
        Mono<Optional<String>> jsonSchemaMono = consulKvService.getValue(jsonSchemaKey)
            .defaultIfEmpty(Optional.empty());

        // Load config params
        Mono<Map<String,String>> configParamsMono = consulKvService.getKeysWithPrefix(configParamsPrefix)
            .flatMap(configKeys -> {
                if (configKeys.isEmpty()) {
                    return Mono.just(new HashMap<String,String>());
                }

                Map<String,String> configParams = new HashMap<>();
                List<Mono<Boolean>> configLoadMonos = new ArrayList<>();

                for (String configKey : configKeys) {
                    String paramName = configKey.substring(configParamsPrefix.length());
                    if (paramName.startsWith("/")) {
                        paramName = paramName.substring(1);
                    }

                    if (paramName.contains("/")) {
                        continue; // Skip nested keys
                    }

                    // Create a final copy of paramName for use in the lambda
                    final String finalParamName = paramName;

                    Mono<Boolean> loadMono = consulKvService.getValue(configKey)
                        .map(valueOpt -> {
                            if (valueOpt.isPresent()) {
                                configParams.put(finalParamName, valueOpt.get());
                                return true;
                            }
                            return false;
                        })
                        .defaultIfEmpty(false);

                    configLoadMonos.add(loadMono);
                }

                if (configLoadMonos.isEmpty()) {
                    return Mono.just(configParams);
                }

                return Mono.zip(configLoadMonos, results -> configParams);
            })
            .defaultIfEmpty(new HashMap<>());

        // Combine all the loaded data
        return Mono.zip(
                kafkaListenTopicsMono,
                kafkaPublishTopicsMono,
                grpcForwardToMono,
                serviceImplMono,
                configParamsMono,
                jsonConfigMono,
                jsonSchemaMono
            )
            .flatMap(tuple -> {
                Optional<String> kafkaListenTopicsOpt = tuple.getT1();
                Optional<String> kafkaPublishTopicsOpt = tuple.getT2();
                Optional<String> grpcForwardToOpt = tuple.getT3();
                Optional<String> serviceImplOpt = tuple.getT4();
                Map<String,String> configParams = tuple.getT5();
                Optional<String> jsonConfigOpt = tuple.getT6();
                Optional<String> jsonSchemaOpt = tuple.getT7();

                // Parse kafka listen topics
                if (kafkaListenTopicsOpt.isPresent() && !kafkaListenTopicsOpt.get().isEmpty()) {
                    List<String> topics = parseCommaSeparatedList(kafkaListenTopicsOpt.get());
                    serviceConfig.setKafkaListenTopics(topics);
                    LOG.debug("Loaded kafka listen topics for service {}: {}", serviceName, topics);
                }

                // Parse kafka publish topics
                if (kafkaPublishTopicsOpt.isPresent() && !kafkaPublishTopicsOpt.get().isEmpty()) {
                    List<String> topics = parseCommaSeparatedList(kafkaPublishTopicsOpt.get());
                    serviceConfig.setKafkaPublishTopics(topics);
                    LOG.debug("Loaded kafka publish topics for service {}: {}", serviceName, topics);
                }

                // Parse grpc forward to
                if (grpcForwardToOpt.isPresent() && !grpcForwardToOpt.get().isEmpty()) {
                    List<String> forwardTo = parseCommaSeparatedList(grpcForwardToOpt.get());
                    serviceConfig.setGrpcForwardTo(forwardTo);
                    LOG.debug("Loaded grpc forward to for service {}: {}", serviceName, forwardTo);
                }

                // Set service implementation
                if (serviceImplOpt.isPresent() && !serviceImplOpt.get().isEmpty()) {
                    serviceConfig.setServiceImplementation(serviceImplOpt.get());
                    LOG.debug("Loaded service implementation for service {}: {}", serviceName, serviceImplOpt.get());
                }

                // Set config params
                if (!configParams.isEmpty()) {
                    serviceConfig.setConfigParams(configParams);
                    LOG.debug("Loaded {} config parameters for service {}", configParams.size(), serviceName);
                }

                // Set JSON configuration and schema
                if (jsonConfigOpt.isPresent() || jsonSchemaOpt.isPresent()) {
                    JsonConfigOptions jsonConfigOptions = serviceConfig.getJsonConfig();

                    if (jsonConfigOpt.isPresent() && !jsonConfigOpt.get().isEmpty()) {
                        jsonConfigOptions.setJsonConfig(jsonConfigOpt.get());
                        LOG.debug("Loaded JSON configuration for service {}", serviceName);
                    }

                    if (jsonSchemaOpt.isPresent() && !jsonSchemaOpt.get().isEmpty()) {
                        jsonConfigOptions.setJsonSchema(jsonSchemaOpt.get());
                        LOG.debug("Loaded JSON schema for service {}", serviceName);
                    }

                    // Validate the configuration against the schema
                    if (jsonConfigOpt.isPresent() && jsonSchemaOpt.isPresent() && 
                        !jsonConfigOpt.get().isEmpty() && !jsonSchemaOpt.get().isEmpty()) {
                        if (!jsonConfigOptions.validateConfig()) {
                            LOG.warn("JSON configuration for service {} failed validation: {}", 
                                serviceName, jsonConfigOptions.getValidationErrors());
                        } else {
                            LOG.debug("JSON configuration for service {} passed validation", serviceName);
                        }
                    }
                }

                try {
                    // Add the service to the pipeline
                    pipeline.addOrUpdateService(serviceConfig);
                    LOG.info("Successfully loaded and added service {} to pipeline {}", serviceName, pipelineName);
                    return Mono.just(true);
                } catch (Exception e) {
                    LOG.error("Error adding service {} to pipeline {}: {}", serviceName, pipelineName, e.getMessage());
                    return Mono.just(false);
                }
            })
            .defaultIfEmpty(false)
            .onErrorResume(e -> {
                LOG.error("Error loading configuration for service {} in pipeline {}", serviceName, pipelineName, e);
                return Mono.just(false);
            });
    }

    /**
     * Parses a comma-separated list string into a list of strings.
     * 
     * @param commaSeparatedList the comma-separated list string
     * @return a list of strings
     */
    private List<String> parseCommaSeparatedList(String commaSeparatedList) {
        if (commaSeparatedList == null || commaSeparatedList.isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(commaSeparatedList.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
}
