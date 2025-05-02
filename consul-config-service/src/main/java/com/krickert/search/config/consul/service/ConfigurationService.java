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
                            .filter(name -> !name.isEmpty() && !name.contains("/")) // Ensure it's just the name part
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
     * // --- NEEDS IMPLEMENTATION ---
     */
    private Mono<Boolean> loadPipelineServices(String pipelineName, PipelineConfigDto pipeline) {
        LOG.warn("loadPipelineServices is not fully implemented yet for pipeline: {}", pipelineName);
        // TODO: Implement service loading logic here
        return Mono.just(true); // Placeholder: Assume success for now
    }
}