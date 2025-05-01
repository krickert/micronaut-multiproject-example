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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing configuration.
 * This service loads configuration from Consul KV store and populates the configuration POJOs.
 * It dynamically discovers and loads all defined pipelines based on keys found in Consul.
 */
@Singleton
@Refreshable // This bean will be recreated on RefreshEvent
public class ConfigurationService implements ApplicationEventListener<StartupEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationService.class);

    private final ConsulKvService consulKvService;
    private final ApplicationConfig applicationConfig;
    private final PipelineConfig pipelineConfig;
    private final String applicationName;
    // Prefix for pipeline definitions in Consul, e.g., "config/pipeline/pipeline.configs/"
    private final String pipelineConfigsRootPrefix;

    /**
     * Constructor with dependencies.
     *
     * @param consulKvService the service for interacting with Consul KV store
     * @param applicationConfig the application configuration POJO
     * @param pipelineConfig the pipeline configuration POJO
     * @param applicationName the name of the application
     * @param consulConfigPath the base path configured for Consul KV
     */
    public ConfigurationService(
            ConsulKvService consulKvService,
            ApplicationConfig applicationConfig,
            PipelineConfig pipelineConfig,
            @Value("${micronaut.application.name}") String applicationName,
            @Value("${consul.client.config.path:config/pipeline}") String consulConfigPath) {
        this.consulKvService = consulKvService;
        this.applicationConfig = applicationConfig;
        this.pipelineConfig = pipelineConfig;
        this.applicationName = applicationName;
        // Calculate the specific prefix for pipeline config structures
        // Use getFullPath to ensure correct formatting relative to base path
        this.pipelineConfigsRootPrefix = consulKvService.getFullPath("pipeline.configs");
        LOG.info("ConfigurationService initialized for application: {}", applicationName);
        LOG.debug("Using pipeline configurations root prefix in Consul: {}", this.pipelineConfigsRootPrefix);
    }

    /**
     * Handles the StartupEvent by loading configuration from Consul KV store.
     * Also called during a configuration refresh.
     *
     * @param event the startup event or refresh event
     */
    @Override
    public void onApplicationEvent(StartupEvent event) {
        LOG.info("Loading/Reloading configuration from Consul KV store...");

        // Load application-specific configuration (if any)
        loadApplicationConfig()
                .doOnSuccess(success -> {
                    if (success) LOG.info("Application configuration loaded/reloaded successfully.");
                    else LOG.warn("Failed to load/reload application configuration.");
                    applicationConfig.markAsEnabled(); // Mark as enabled regardless
                })
                .subscribe();

        // Load all pipeline configurations dynamically
        loadAllPipelineConfigurations()
                .doOnSuccess(success -> {
                    if (success) LOG.info("Pipeline configurations loaded/reloaded successfully.");
                    else LOG.warn("Failed to load/reload pipeline configurations.");
                    pipelineConfig.markAsEnabled(); // Mark as enabled regardless
                })
                .subscribe();
    }

    /**
     * Handles ConfigChangeEvent by reloading the affected configuration.
     * Currently, reloads everything for simplicity upon any relevant change.
     *
     * @param event the configuration change event
     */
    public void onConfigChange(ConfigChangeEvent event) {
        String keyPrefix = event.getKeyPrefix(); // Prefix from the change notifier
        LOG.info("Configuration change detected potentially affecting key prefix: {}", keyPrefix);

        // Use the consul service's base path for comparison
        String consulBasePath = consulKvService.getConfigPath(); // e.g., "config/pipeline/"

        // Check if the change is within the consul config path
        if (keyPrefix != null && keyPrefix.startsWith(consulBasePath)) {
            String relativePrefix = keyPrefix.substring(consulBasePath.length());

            if (relativePrefix.startsWith(applicationName + "/")) { // Check relative prefix for app config
                LOG.info("Change detected affecting application config (prefix: {}). Reloading.", relativePrefix);
                loadApplicationConfig().subscribe();
            } else if (relativePrefix.startsWith("pipeline.configs")) { // Check relative prefix for pipeline config
                LOG.info("Change detected affecting pipeline config (prefix: {}). Reloading all pipelines.", relativePrefix);
                loadAllPipelineConfigurations().subscribe();
            } else {
                LOG.debug("Change event prefix '{}' (relative '{}') does not match known configurations. Ignoring.", keyPrefix, relativePrefix);
            }
        } else {
            LOG.debug("Change event prefix '{}' is outside the Consul config path '{}'. Ignoring.", keyPrefix, consulBasePath);
        }
    }

    /**
     * Loads application configuration from Consul KV store.
     * (Placeholder for any app-specific config loading logic if needed)
     *
     * @return a Mono that emits true if the operation was successful, false otherwise.
     */
    private Mono<Boolean> loadApplicationConfig() {
        applicationConfig.setApplicationName(applicationName);
        LOG.debug("Loading application-specific configuration (if any) for '{}'...", applicationName);
        // Add specific logic here if needed to load keys like:
        // consulKvService.getFullPath(applicationName + "/config/someSetting")
        return Mono.just(true); // Assume success for now
    }

    /**
     * Loads ALL pipeline configurations dynamically from Consul KV store by listing keys.
     *
     * @return a Mono that emits true if the overall loading process seems successful, false otherwise.
     */
    private Mono<Boolean> loadAllPipelineConfigurations() {
        LOG.info("Starting dynamic discovery and loading of all pipeline configurations from Consul prefix: {}", pipelineConfigsRootPrefix);

        // Use the getKeysRecursive method which should handle the prefix correctly
        return consulKvService.getKeysRecursive("pipeline.configs") // Use relative path here
                .flatMap(keys -> {
                    if (keys == null || keys.isEmpty()) {
                        LOG.warn("No pipeline configuration keys found under prefix: {}", pipelineConfigsRootPrefix);
                        pipelineConfig.setPipelines(new HashMap<>()); // Reset to empty map
                        return Mono.just(true); // No configs found is not an error itself
                    }

                    LOG.debug("Found {} potential configuration keys under {}", keys.size(), pipelineConfigsRootPrefix);

                    // Extract unique pipeline names from the full keys returned by Consul
                    // Example key: "config/pipeline/pipeline.configs.pipeline1/service/..."
                    Set<String> pipelineNames = keys.stream()
                            .map(this::extractPipelineNameFromKey) // Use the helper method
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toSet());

                    if (pipelineNames.isEmpty()) {
                        LOG.warn("Found keys under prefix '{}', but could not parse any pipeline names.", pipelineConfigsRootPrefix);
                        pipelineConfig.setPipelines(new HashMap<>()); // Reset to empty map
                        return Mono.just(true);
                    }

                    LOG.info("Discovered pipeline names: {}", pipelineNames);

                    // Create a new map for the loaded configurations (thread-safe for concurrent loading)
                    Map<String, PipelineConfigDto> loadedPipelines = new ConcurrentHashMap<>();

                    // Load each pipeline configuration concurrently
                    return Flux.fromIterable(pipelineNames)
                            .flatMap(name -> loadSinglePipelineConfiguration(name) // Load details for one pipeline
                                    .doOnNext(dtoOpt -> dtoOpt.ifPresent(dto -> {
                                        LOG.debug("Successfully loaded DTO for pipeline: {}", name);
                                        loadedPipelines.put(name, dto);
                                    }))
                                    .doOnError(e -> LOG.error("Error loading individual pipeline config for '{}': {}", name, e.getMessage()))
                            )
                            .then(Mono.fromRunnable(() -> {
                                // Atomically replace the map in the PipelineConfig bean
                                pipelineConfig.setPipelines(loadedPipelines);
                                LOG.info("Finished loading {} pipeline configurations: {}", loadedPipelines.size(), loadedPipelines.keySet());
                            }))
                            .thenReturn(true); // Return true indicating overall success attempt
                })
                .onErrorResume(e -> {
                    LOG.error("Error during dynamic pipeline configuration loading: {}", e.getMessage(), e);
                    pipelineConfig.setPipelines(new HashMap<>()); // Clear potentially partial data on error
                    return Mono.just(false); // Indicate failure
                })
                .defaultIfEmpty(true); // If getKeysRecursive returns empty Mono, consider it success
    }

    /**
     * Extracts the pipeline name from a full Consul key path.
     * Assumes keys are like "config/pipeline/pipeline.configs.pipeline1/service/..."
     *
     * @param fullKey The full key from Consul (including the base config path).
     * @return Optional containing the pipeline name (e.g., "pipeline1") if found.
     */
    private Optional<String> extractPipelineNameFromKey(String fullKey) {
        // Example fullKey: "config/pipeline/pipeline.configs.pipeline1/service/chunker/kafka-listen-topics/"
        String prefixToRemove = this.pipelineConfigsRootPrefix; // e.g., "config/pipeline/pipeline.configs/"
        if (fullKey != null && fullKey.startsWith(prefixToRemove)) {
            String remaining = fullKey.substring(prefixToRemove.length()); // e.g., "pipeline1/service/chunker/kafka-listen-topics/"
            // Find the first segment after the prefix
            int firstSlash = remaining.indexOf('/');
            if (firstSlash > 0) {
                String pipelineName = remaining.substring(0, firstSlash);
                return Optional.of(pipelineName);
            } else if (!remaining.isEmpty() && !remaining.contains("/")) {
                // Case where the key is just the pipeline name itself (unlikely but handle)
                return Optional.of(remaining);
            }
        }
        LOG.trace("Could not extract pipeline name from key: {}", fullKey);
        return Optional.empty();
    }

    /**
     * Extracts the service name from a full Consul key path.
     * Assumes keys are like "config/pipeline/pipeline.configs.pipeline1/service/chunker/kafka-listen-topics/"
     *
     * @param fullKey The full key from Consul.
     * @param pipelineName The name of the pipeline context.
     * @return Optional containing the service name (e.g., "chunker") if found.
     */
    private Optional<String> extractServiceNameFromKey(String fullKey, String pipelineName) {
        // Example fullKey: "config/pipeline/pipeline.configs.pipeline1/service/chunker/kafka-listen-topics/"
        String servicePrefix = this.pipelineConfigsRootPrefix + pipelineName + "/service/"; // e.g., "config/pipeline/pipeline.configs.pipeline1/service/"
        if (fullKey != null && fullKey.startsWith(servicePrefix)) {
            String remaining = fullKey.substring(servicePrefix.length()); // e.g., "chunker/kafka-listen-topics/"
            int firstSlash = remaining.indexOf('/');
            if (firstSlash > 0) {
                String serviceName = remaining.substring(0, firstSlash);
                return Optional.of(serviceName);
            }
        }
        LOG.trace("Could not extract service name from key: {} within pipeline {}", fullKey, pipelineName);
        return Optional.empty();
    }


    /**
     * Loads the configuration for a single, specific pipeline dynamically from Consul.
     *
     * @param pipelineName the name of the pipeline to load.
     * @return a Mono emitting an Optional containing the loaded PipelineConfigDto, or empty if not found/error.
     */
    private Mono<Optional<PipelineConfigDto>> loadSinglePipelineConfiguration(String pipelineName) {
        LOG.debug("Dynamically loading configuration for pipeline: {}", pipelineName);
        PipelineConfigDto pipelineDto = new PipelineConfigDto(pipelineName);
        // Define the prefix for listing service keys within this pipeline
        String pipelineServicePrefix = "pipeline.configs." + pipelineName + ".service"; // Relative path for getKeysRecursive

        return consulKvService.getKeysRecursive(pipelineServicePrefix)
                .flatMap(serviceKeys -> {
                    if (serviceKeys == null || serviceKeys.isEmpty()) {
                        LOG.warn("No service configuration keys found for pipeline: {}", pipelineName);
                        return Mono.just(pipelineDto); // Return DTO with empty service map
                    }

                    LOG.debug("Found {} potential service keys for pipeline {}", serviceKeys.size(), pipelineName);

                    // Extract unique service names from the full keys
                    Set<String> serviceNames = serviceKeys.stream()
                            .map(key -> extractServiceNameFromKey(key, pipelineName)) // Use helper
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .collect(Collectors.toSet());

                    LOG.debug("Discovered services for pipeline {}: {}", pipelineName, serviceNames);

                    if (serviceNames.isEmpty()) {
                        LOG.warn("Found service keys for pipeline {}, but could not parse any service names.", pipelineName);
                        return Mono.just(pipelineDto); // Return DTO with empty service map
                    }

                    Map<String, ServiceConfigurationDto> loadedServices = new ConcurrentHashMap<>();

                    // Load configuration for each discovered service concurrently
                    return Flux.fromIterable(serviceNames)
                            .flatMap(serviceName -> loadServiceConfiguration(pipelineName, serviceName)
                                    .doOnNext(serviceDtoOpt -> serviceDtoOpt.ifPresent(
                                            serviceDto -> loadedServices.put(serviceName, serviceDto))
                                    )
                                    .doOnError(e -> LOG.error("Error loading config for service '{}' in pipeline '{}': {}", serviceName, pipelineName, e.getMessage()))
                            )
                            .then(Mono.fromRunnable(() -> {
                                pipelineDto.setServices(loadedServices); // Set the map of loaded services
                                LOG.debug("Finished loading {} services for pipeline {}", loadedServices.size(), pipelineName);
                            }))
                            .thenReturn(pipelineDto); // Return the populated DTO
                })
                .map(Optional::of) // Wrap the final DTO in Optional
                .onErrorResume(e -> {
                    LOG.error("Error loading configuration for pipeline {}: {}", pipelineName, e.getMessage(), e);
                    return Mono.just(Optional.empty()); // Return empty optional on error during key listing or processing
                })
                .defaultIfEmpty(Optional.of(pipelineDto)); // If getKeysRecursive returns empty Mono, return DTO with empty services
    }

    /**
     * Loads the configuration for a specific service within a specific pipeline by reading individual keys.
     *
     * @param pipelineName The name of the pipeline.
     * @param serviceName The name of the service.
     * @return A Mono emitting an Optional containing the loaded ServiceConfigurationDto, or empty if essential parts are missing or error occurs.
     */
    private Mono<Optional<ServiceConfigurationDto>> loadServiceConfiguration(String pipelineName, String serviceName) {
        LOG.debug("Loading configuration details for service '{}' in pipeline '{}'", serviceName, pipelineName);
        // Base key relative to consul path for this service's config
        String relativeBaseKey = "pipeline.configs." + pipelineName + ".service." + serviceName;
        ServiceConfigurationDto dto = new ServiceConfigurationDto();
        dto.setName(serviceName); // Set the name

        // Use flatMapSequential to ensure order but load values potentially concurrently
        return Mono.zip(
                        // Fetch each property, returning Optional.empty() if not found or error
                        getValueOptional(relativeBaseKey + ".kafka-listen-topics"),
                        getValueOptional(relativeBaseKey + ".kafka-publish-topics"),
                        getValueOptional(relativeBaseKey + ".grpc-forward-to"),
                        getValueOptional(relativeBaseKey + ".serviceImplementation"),
                        loadConfigParams(relativeBaseKey + ".configParams") // Special handling for map
                )
                .map(tuple -> {
                    // Populate the DTO, parsing list values
                    tuple.getT1().ifPresent(val -> dto.setKafkaListenTopics(parseListValue(val)));
                    tuple.getT2().ifPresent(val -> dto.setKafkaPublishTopics(parseListValue(val)));
                    tuple.getT3().ifPresent(val -> {
                        if (!"null".equalsIgnoreCase(val.trim())) { // Handle explicit "null" string case-insensitively
                            dto.setGrpcForwardTo(parseListValue(val));
                        } else {
                            dto.setGrpcForwardTo(null); // Treat "null" string as null list
                        }
                    });
                    tuple.getT4().ifPresent(dto::setServiceImplementation);

                    // Set configParams map
                    if (!tuple.getT5().isEmpty()) {
                        PipestepConfigOptions options = new PipestepConfigOptions();
                        options.putAll(tuple.getT5());
                        dto.setConfigParams(options);
                    } else {
                        dto.setConfigParams(null); // Or new PipestepConfigOptions()
                    }

                    LOG.debug("Successfully populated DTO for service: {} in pipeline {}", serviceName, pipelineName);
                    return Optional.of(dto); // Return populated DTO
                })
                .onErrorResume(e -> {
                    LOG.error("Error processing loaded values for service {} in pipeline {}: {}", serviceName, pipelineName, e.getMessage());
                    return Mono.just(Optional.empty()); // Return empty on error during zip/map
                })
                .defaultIfEmpty(Optional.empty()); // Should not happen with zip unless upstream Monos error AND return empty
    }

    /**
     * Helper to get a single value, returning Mono<Optional<String>>.
     */
    private Mono<Optional<String>> getValueOptional(String relativeKey) {
        return consulKvService.getValue(relativeKey)
                .onErrorReturn(Optional.empty()); // Ensure errors result in empty Optional Mono
    }

    /**
     * Loads configParams map for a service by listing keys under its configParams prefix.
     *
     * @param relativeConfigParamsPrefix e.g., "pipeline.configs.pipe1.service.chunker.configParams"
     * @return A Mono emitting the loaded map (empty if none found or error).
     */
    private Mono<Map<String, String>> loadConfigParams(String relativeConfigParamsPrefix) {
        LOG.debug("Loading configParams under relative prefix: {}", relativeConfigParamsPrefix);
        // Get keys under the specific prefix for configParams
        return consulKvService.getKeysRecursive(relativeConfigParamsPrefix)
                .flatMapMany(Flux::fromIterable) // Convert List<String> to Flux<String>
                .flatMap(fullParamKey -> {
                    // Extract the param name relative to the prefix
                    String configParamPrefixWithSlash = consulKvService.getFullPath(relativeConfigParamsPrefix); // Ensures trailing slash
                    if (!fullParamKey.startsWith(configParamPrefixWithSlash)) {
                        LOG.warn("Key '{}' does not start with expected configParams prefix '{}'. Skipping.", fullParamKey, configParamPrefixWithSlash);
                        return Mono.empty(); // Skip keys not matching expected structure
                    }
                    String paramName = fullParamKey.substring(configParamPrefixWithSlash.length()).replaceAll("/$", ""); // Get part after prefix, remove trailing slash

                    if (paramName.isEmpty() || paramName.contains("/")) {
                        LOG.warn("Invalid structure for configParam key '{}'. Skipping.", fullParamKey);
                        return Mono.empty(); // Skip directory-like keys or empty names
                    }

                    // Get the value for this specific parameter key
                    return consulKvService.getValue(fullParamKey) // Use full key to get value
                            .filter(Optional::isPresent)
                            .map(Optional::get)
                            .map(value -> Map.entry(paramName, value)); // Create Map.Entry
                })
                .collectMap(Map.Entry::getKey, Map.Entry::getValue) // Collect into a Map
                .doOnNext(map -> LOG.debug("Loaded {} configParams for prefix '{}'", map.size(), relativeConfigParamsPrefix))
                .defaultIfEmpty(new HashMap<>()); // Return empty map if no params found or error
    }


    /**
     * Parses a comma-separated string value from Consul into a List of strings.
     * Handles null, empty, and quoted values if necessary (basic split/trim implemented).
     *
     * @param value The string value from Consul.
     * @return A List of strings, or an empty list if input is null/empty.
     */
    private List<String> parseListValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        // Simple split by comma and trim whitespace
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty()) // Remove empty strings resulting from extra commas
                .collect(Collectors.toList());
        // Add more sophisticated parsing here if values can contain commas or need quoting.
    }
}