package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Singleton
public class DynamicConfigurationManagerImpl implements DynamicConfigurationManager {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImpl.class);

    private final String clusterName;
    private final ConsulConfigFetcher consulConfigFetcher;
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    private final ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher;
    private final CopyOnWriteArrayList<Consumer<ClusterConfigUpdateEvent>> listeners = new CopyOnWriteArrayList<>();

    public DynamicConfigurationManagerImpl(
            @Value("${app.config.cluster-name}") String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher
    ) {
        this.clusterName = clusterName;
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        LOG.info("DynamicConfigurationManagerImpl created for cluster: {}", clusterName);
    }

    @PostConstruct
    public void postConstructInitialize() {
        LOG.info("Initializing DynamicConfigurationManager for cluster: {}", clusterName);
        initialize(this.clusterName);
    }

    @Override
    public void initialize(String clusterNameFromParam) {
        if (!this.clusterName.equals(clusterNameFromParam)) {
            LOG.warn("Initialize called with different cluster name '{}', expected '{}'. Using configured name: {}",
                    clusterNameFromParam, this.clusterName, this.clusterName);
        }
        try {
            consulConfigFetcher.connect();
            LOG.info("Attempting initial configuration load for cluster: {}", this.clusterName);

            try {
                Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(this.clusterName);
                if (initialClusterConfigOpt.isPresent()) {
                    PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                    LOG.info("Initial configuration fetched for cluster '{}'. Processing...", this.clusterName);
                    processConsulUpdate(WatchCallbackResult.success(initialConfig), "Initial Load");
                } else {
                    LOG.warn("No initial configuration found for cluster '{}'. Watch will pick up first appearance or deletion.", this.clusterName);
                    // Current behavior: If no initial config, let the watch be the primary driver.
                    // This is acceptable as the system might still become consistent once the watch provides data.
                }
            } catch (Exception fetchEx) {
                // This catch block handles failures ONLY during the initial fetch.
                // It's reasonable to log this and still attempt to start the watch,
                // as the watch might succeed later.
                LOG.error("Error during initial configuration fetch for cluster '{}': {}. Will still attempt to start watch.",
                        this.clusterName, fetchEx.getMessage(), fetchEx);
                // Process this as a failure for the initial state, but don't stop initialization.
                processConsulUpdate(WatchCallbackResult.failure(fetchEx), "Initial Load Fetch Error");
            }

            // If connect() succeeded and initial fetch (even if failed) didn't stop us,
            // setting up the watch is critical.
            consulConfigFetcher.watchClusterConfig(this.clusterName, this::handleConsulWatchUpdate);
            LOG.info("Consul watch established for cluster configuration: {}", this.clusterName);

        } catch (Exception e) {
            // This outer catch block handles failures from consulConfigFetcher.connect()
            // or consulConfigFetcher.watchClusterConfig() setup.
            // These are critical failures for the manager's core functionality.
            LOG.error("CRITICAL: Failed to initialize DynamicConfigurationManager (connect or watch setup) for cluster '{}': {}",
                    this.clusterName, e.getMessage(), e);
            // Re-throw as a custom runtime exception to make the initialization failure explicit.
            // This will typically cause the bean creation to fail if called from @PostConstruct.
            throw new ConfigurationManagerInitializationException(
                    "Failed to initialize Consul connection or watch for cluster " + this.clusterName, e);
        }
    }

    private void handleConsulWatchUpdate(WatchCallbackResult watchResult) {
        processConsulUpdate(watchResult, "Consul Watch Update");
    }

    private void processConsulUpdate(WatchCallbackResult watchResult, String updateSource) {

        Optional<PipelineClusterConfig> oldConfigForEvent = cachedConfigHolder.getCurrentConfig();

        if (watchResult.hasError()) {
            LOG.error("CRITICAL: Error received from Consul source '{}' for cluster '{}': {}. Keeping previous configuration.",
                    updateSource, this.clusterName, watchResult.error().map(Throwable::getMessage).orElse("Unknown error"));
            watchResult.error().ifPresent(e -> LOG.debug("Consul source error details:", e));
            return;
        }

        if (watchResult.deleted()) { // Uses the boolean accessor from the record instance
            LOG.warn("PipelineClusterConfig for cluster '{}' indicated as deleted by source '{}'. Clearing local cache and notifying listeners.",
                    this.clusterName, updateSource);
            cachedConfigHolder.clearConfiguration();
            if (oldConfigForEvent.isPresent()) {
                PipelineClusterConfig effectivelyEmptyConfig = new PipelineClusterConfig(this.clusterName);
                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfigForEvent, effectivelyEmptyConfig);
                publishEvent(event);
            } else {
                 LOG.info("Cache was already empty or no previous config to compare for deletion event from source '{}' for cluster '{}'.",
                         updateSource, this.clusterName);
            }
            return;
        }

        if (watchResult.config().isPresent()) {
            PipelineClusterConfig newConfig = watchResult.config().get();
            try {
                Map<SchemaReference, String> schemaCacheForNewConfig = new HashMap<>();
                if (newConfig.pipelineModuleMap() != null && newConfig.pipelineModuleMap().availableModules() != null) {
                    for (PipelineModuleConfiguration moduleConfig : newConfig.pipelineModuleMap().availableModules().values()) {
                        if (moduleConfig.customConfigSchemaReference() != null) {
                            SchemaReference ref = moduleConfig.customConfigSchemaReference();
                            Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.subject(), ref.version());
                            if (schemaDataOpt.isPresent() && schemaDataOpt.get().schemaContent() != null) {
                                schemaCacheForNewConfig.put(ref, schemaDataOpt.get().schemaContent());
                            } else {
                                LOG.warn("Schema content not found for reference {} during processing for source '{}'. Validation will likely fail for steps using this module.",
                                        ref, updateSource);
                            }
                        }
                    }
                }
                LOG.debug("Fetched {} schema references for validation for source '{}'.", schemaCacheForNewConfig.size(), updateSource);

                ValidationResult validationResult = configurationValidator.validate(
                    newConfig,
                    (schemaRef) -> Optional.ofNullable(schemaCacheForNewConfig.get(schemaRef))
                );

                if (validationResult.isValid()) {
                    LOG.info("Configuration for cluster '{}' from source '{}' validated successfully. Updating cache and notifying listeners.",
                            this.clusterName, updateSource);
                    cachedConfigHolder.updateConfiguration(newConfig, schemaCacheForNewConfig);
                    ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfigForEvent, newConfig);
                    publishEvent(event);
                } else {
                    LOG.error("CRITICAL: New configuration for cluster '{}' from source '{}' failed validation. Errors: {}. Keeping previous configuration.",
                            this.clusterName, updateSource, validationResult.errors());
                }
            } catch (Exception e) {
                LOG.error("CRITICAL: Exception during processing of new configuration from source '{}' for cluster '{}': {}. Keeping previous configuration.",
                        updateSource, this.clusterName, e.getMessage(), e);
            }
        } else {
            LOG.warn("Received ambiguous WatchCallbackResult (no config, no error, not deleted) from source '{}' for cluster '{}'. No action taken.",
                     updateSource, this.clusterName);
        }
    }

    private void publishEvent(ClusterConfigUpdateEvent event) {
        try {
            eventPublisher.publishEvent(event);
            listeners.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.error("Error invoking direct config update listener for cluster {}: {}", this.clusterName, e.getMessage(), e);
                }
            });
            LOG.info("Notified listeners of configuration update for cluster '{}'. Old config present: {}, New config cluster: {}",
                this.clusterName, event.oldConfig().isPresent(), event.newConfig().clusterName());
        } catch (Exception e) {
            LOG.error("Error publishing ClusterConfigUpdateEvent for cluster {}: {}", this.clusterName, e.getMessage(), e);
        }
    }

    @Override
    public Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig() {
        return cachedConfigHolder.getCurrentConfig();
    }

    @Override
    public Optional<String> getSchemaContent(SchemaReference schemaRef) {
        return cachedConfigHolder.getSchemaContent(schemaRef);
    }

    @Override
    public void registerConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        listeners.add(listener);
    }

    @Override
    public void unregisterConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        listeners.remove(listener);
    }

    @PreDestroy
    @Override
    public void shutdown() {
        LOG.info("Shutting down DynamicConfigurationManager for cluster: {}", clusterName);
        if (consulConfigFetcher != null) {
            try {
                consulConfigFetcher.close();
            } catch (Exception e) {
                LOG.error("Error shutting down ConsulConfigFetcher: {}", e.getMessage(), e);
            }
        }
        listeners.clear();
    }
}