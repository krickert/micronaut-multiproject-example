package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.schema.registry.model.SchemaVersionData; // Assuming this is the model holding schemaContent

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct; // Alternative to ApplicationStartupEvent for eager init
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

    // For direct listener registration if not solely relying on Micronaut events
    private final CopyOnWriteArrayList<Consumer<ClusterConfigUpdateEvent>> listeners = new CopyOnWriteArrayList<>();


    public DynamicConfigurationManagerImpl(
            @Value("${app.config.cluster-name}") String clusterName, // Injected from application.yml/properties
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

    /**
     * Initializes the configuration manager upon application startup.
     * Connects to Consul, performs an initial configuration load, and sets up watches.
     */
    @PostConstruct // Or use @EventListener(ApplicationStartupEvent.class)
    public void postConstructInitialize() {
        LOG.info("Initializing DynamicConfigurationManager for cluster: {}", clusterName);
        initialize(this.clusterName);
    }


    @Override
    public void initialize(String clusterName) {
        if (!this.clusterName.equals(clusterName)) {
            LOG.warn("Initialize called with different cluster name '{}', expected '{}'. Using configured name.", clusterName, this.clusterName);
            // Potentially throw an exception or reconfigure if dynamic cluster assignment is a feature
        }
        try {
            consulConfigFetcher.connect(); // Ensure connection is established

            LOG.info("Attempting initial configuration load for cluster: {}", this.clusterName);
            Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(this.clusterName);

            if (initialClusterConfigOpt.isEmpty()) {
                LOG.error("CRITICAL: Initial PipelineClusterConfig for cluster '{}' not found in Consul. Engine may not function.", this.clusterName);
                // Consider application behavior: halt, degraded mode, or keep trying?
                // For now, we proceed to set up watch, which might pick it up later.
                // Or throw new IllegalStateException("...") to prevent startup without config.
            }

            // Even if initial config is empty, we set up the watch.
            // The handleConsulUpdate will process the initial state if the watch fires immediately,
            // or process the first valid config that appears.
            // Alternatively, the initial load could be the sole responsibility of the watch's first trigger.
            // For clarity, let's process the initially fetched config if present.

            if (initialClusterConfigOpt.isPresent()) {
                PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                LOG.info("Initial configuration fetched for cluster '{}'. Validating...", this.clusterName);
                processAndCacheConfigUpdate(Optional.empty(), initialConfig, "Initial Load");
            } else {
                LOG.warn("No initial configuration found for cluster '{}'. Waiting for Consul watch updates.", this.clusterName);
                // Potentially clear any stale cache if this implies a "reset"
                // cachedConfigHolder.clearConfiguration();
            }

            // Set up the watch regardless of initial load success to pick up future changes or first appearance
            consulConfigFetcher.watchClusterConfig(this.clusterName, this::handleConsulClusterConfigUpdate);
            LOG.info("Consul watch established for cluster configuration: {}", this.clusterName);

        } catch (Exception e) {
            LOG.error("CRITICAL: Failed to initialize DynamicConfigurationManager for cluster '{}': {}", this.clusterName, e.getMessage(), e);
            // This is a severe startup failure. Depending on policy, rethrow or enter a failed state.
            // For now, we log and the application might continue in a non-functional state regarding dynamic config.
        }
    }

    /**
     * Callback method invoked by ConsulConfigFetcher when the cluster configuration in Consul changes.
     *
     * @param newClusterConfigOpt Optional containing the new PipelineClusterConfig, or empty if deleted.
     */
    private void handleConsulClusterConfigUpdate(Optional<PipelineClusterConfig> newClusterConfigOpt) {
        LOG.info("Consul watch triggered for cluster '{}'. New config present: {}", this.clusterName, newClusterConfigOpt.isPresent());

        if (newClusterConfigOpt.isEmpty()) {
            LOG.warn("PipelineClusterConfig for cluster '{}' was deleted from Consul. Clearing local cache and notifying listeners.", this.clusterName);
            Optional<PipelineClusterConfig> oldConfig = cachedConfigHolder.getCurrentConfig();
            cachedConfigHolder.clearConfiguration();
            // Create a "deleted" event or a specific type of update if needed.
            // For now, publishing with newConfig as effectively null (though our event doesn't support that directly)
            // Or, better, define a different event type or handle this as a special case.
            // Let's assume for now this means the system should stop processing for this cluster or revert to a default empty state.
            // This needs careful thought on desired behavior.
            // For a robust system, this might trigger alerts and a specific "config unavailable" state.
            // We can publish an event with newConfig being a "minimal/empty" config or handle it differently.
            // For simplicity in this skeleton, we will log and clear. A real app might have specific state transitions.
            if (oldConfig.isPresent()) { // Publish an event indicating the config was effectively removed
                 // Create a placeholder empty config to satisfy event, or modify event structure
                PipelineClusterConfig effectivelyEmptyConfig = new PipelineClusterConfig();
                effectivelyEmptyConfig.setClusterName(this.clusterName); // Basic identifier

                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfig, effectivelyEmptyConfig); // Or a more specific "ConfigDeletedEvent"
                eventPublisher.publishEvent(event);
                listeners.forEach(listener -> listener.accept(event));
                LOG.info("Notified listeners of configuration removal for cluster '{}'.", this.clusterName);
            }
            return;
        }

        PipelineClusterConfig newConfig = newClusterConfigOpt.get();
        Optional<PipelineClusterConfig> oldConfig = cachedConfigHolder.getCurrentConfig(); // Get current before attempting update
        processAndCacheConfigUpdate(oldConfig, newConfig, "Consul Watch Update");
    }

    /**
     * Centralized logic to process a potential configuration update (either initial or from watch).
     * This involves fetching schemas, validating, caching, and publishing events.
     *
     * @param oldConfig The state of the PipelineClusterConfig *before* this update attempt.
     * This will be Optional.empty() for the very first successful load.
     * @param newConfig The new PipelineClusterConfig to process.
     * @param updateSource A string indicating the source of the update (e.g., "Initial Load", "Consul Watch Update").
     */
    private void processAndCacheConfigUpdate(Optional<PipelineClusterConfig> oldConfig, PipelineClusterConfig newConfig, String updateSource) {
        try {
            // 1. Fetch all referenced schemas for the new configuration
            Map<SchemaReference, String> schemaCacheForNewConfig = new HashMap<>();
            if (newConfig.getPipelineModuleMap() != null && newConfig.getPipelineModuleMap().getAvailableModules() != null) {
                for (PipelineModuleConfiguration moduleConfig : newConfig.getPipelineModuleMap().getAvailableModules().values()) {
                    if (moduleConfig.getCustomConfigSchemaReference() != null) {
                        SchemaReference ref = moduleConfig.getCustomConfigSchemaReference();
                        Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.getSubject(), ref.getVersion());
                        if (schemaDataOpt.isPresent() && schemaDataOpt.get().getSchemaContent() != null) {
                            schemaCacheForNewConfig.put(ref, schemaDataOpt.get().getSchemaContent());
                        } else {
                            LOG.warn("Schema content not found for reference {} during {}. Validation might fail for steps using this module.", ref, updateSource);
                            // The validator should catch issues stemming from a missing schema if a step requires it.
                        }
                    }
                }
            }
            LOG.debug("Fetched {} schema references for validation during {}.", schemaCacheForNewConfig.size(), updateSource);

            // 2. Validate the new configuration
            ValidationResult validationResult = configurationValidator.validate(
                    newConfig,
                    (schemaRef) -> Optional.ofNullable(schemaCacheForNewConfig.get(schemaRef))
            );

            if (validationResult.isValid()) {
                LOG.info("Configuration for cluster '{}' validated successfully ({}) . Updating cache and notifying listeners.", this.clusterName, updateSource);

                // Update the central cache
                cachedConfigHolder.updateConfiguration(newConfig, schemaCacheForNewConfig);

                // Publish the event with the 'oldConfig' that was passed in (representing state before this update)
                // and the 'newConfig' that has just been validated and cached.
                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfig, newConfig);
                eventPublisher.publishEvent(event);
                listeners.forEach(listener -> listener.accept(event));
                LOG.info("Notified listeners of configuration update for cluster '{}' ({}).", this.clusterName, updateSource);
            } else {
                LOG.error("CRITICAL: New configuration for cluster '{}' ({}) failed validation. Errors: {}. Keeping previous configuration.",
                        this.clusterName, updateSource, validationResult.errors());
                // Optionally publish a "ConfigUpdateFailedEvent"
            }
        } catch (Exception e) {
            LOG.error("CRITICAL: Exception during processing of configuration update for cluster '{}' ({}): {}",
                    this.clusterName, updateSource, e.getMessage(), e);
            // Keep previous configuration (which is implicitly done by not calling cachedConfigHolder.updateConfiguration).
        }
    }


    @Override
    public Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig() {
        return cachedConfigHolder.getCurrentConfig();
    }

    @Override
    public Optional<String> getSchemaContent(SchemaReference schemaRef) {
        // First, try the main cache holder which should have schemas for the *active* config
        Optional<String> cachedSchema = cachedConfigHolder.getSchemaContent(schemaRef);
        if (cachedSchema.isPresent()) {
            return cachedSchema;
        }
        // Fallback: If a component needs a schema not in the current active config's cache
        // (e.g., an admin tool previewing an old version), it might fetch directly.
        // However, the primary use case is for schemas related to the active config.
        // For simplicity, this manager currently only exposes schemas linked to the active config.
        // If direct fetching of any arbitrary schema is needed through this manager,
        // it could delegate to consulConfigFetcher.fetchSchemaVersionData(ref.getSubject(), ref.getVersion())
        // and extract content, but that bypasses the "active config cache" concept.
        LOG.warn("Schema content for {} not found in active cache. It might not be referenced by the current cluster config.", schemaRef);
        return Optional.empty();
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
                consulConfigFetcher.close(); // This should stop watches and close client
            } catch (Exception e) {
                LOG.error("Error shutting down ConsulConfigFetcher: {}", e.getMessage(), e);
            }
        }
        listeners.clear();
    }
}