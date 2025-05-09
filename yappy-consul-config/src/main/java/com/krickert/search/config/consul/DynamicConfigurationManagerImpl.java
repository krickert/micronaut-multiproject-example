package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
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
    public void initialize(String clusterName) {
        if (!this.clusterName.equals(clusterName)) {
            LOG.warn("Initialize called with different cluster name '{}', expected '{}'. Using configured name.", clusterName, this.clusterName);
        }
        try {
            consulConfigFetcher.connect();
            LOG.info("Attempting initial configuration load for cluster: {}", this.clusterName);
            Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(this.clusterName);

            if (initialClusterConfigOpt.isPresent()) {
                PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                LOG.info("Initial configuration fetched for cluster '{}'. Validating...", this.clusterName);
                processAndCacheConfigUpdate(Optional.empty(), initialConfig, "Initial Load");
            } else {
                LOG.warn("No initial configuration found for cluster '{}'. Waiting for Consul watch updates.", this.clusterName);
                // Consider clearing cache if this is a definitive "no config exists" state
                // cachedConfigHolder.clearConfiguration();
            }
            consulConfigFetcher.watchClusterConfig(this.clusterName, this::handleConsulClusterConfigUpdate);
            LOG.info("Consul watch established for cluster configuration: {}", this.clusterName);
        } catch (Exception e) {
            LOG.error("CRITICAL: Failed to initialize DynamicConfigurationManager for cluster '{}': {}", this.clusterName, e.getMessage(), e);
        }
    }

    private void handleConsulClusterConfigUpdate(Optional<PipelineClusterConfig> newClusterConfigOpt) {
        LOG.info("Consul watch triggered for cluster '{}'. New config present: {}", this.clusterName, newClusterConfigOpt.isPresent());
        Optional<PipelineClusterConfig> oldConfigFromCache = cachedConfigHolder.getCurrentConfig();

        if (newClusterConfigOpt.isEmpty()) {
            LOG.warn("PipelineClusterConfig for cluster '{}' was deleted from Consul. Clearing local cache and notifying listeners.", this.clusterName);
            cachedConfigHolder.clearConfiguration();
            if (oldConfigFromCache.isPresent()) {
                // Using the convenience constructor for PipelineClusterConfig for a minimal representation
                PipelineClusterConfig effectivelyEmptyConfig = new PipelineClusterConfig(this.clusterName);
                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfigFromCache, effectivelyEmptyConfig);
                publishEvent(event);
            }
            return;
        }
        processAndCacheConfigUpdate(oldConfigFromCache, newClusterConfigOpt.get(), "Consul Watch Update");
    }

    private void processAndCacheConfigUpdate(Optional<PipelineClusterConfig> oldConfig, PipelineClusterConfig newConfig, String updateSource) {
        try {
            Map<SchemaReference, String> schemaCacheForNewConfig = new HashMap<>();
            // Using record accessor pipelineModuleMap() and availableModules()
            if (newConfig.pipelineModuleMap() != null && newConfig.pipelineModuleMap().availableModules() != null) {
                // Using record accessor customConfigSchemaReference()
                for (PipelineModuleConfiguration moduleConfig : newConfig.pipelineModuleMap().availableModules().values()) {
                    if (moduleConfig.customConfigSchemaReference() != null) {
                        SchemaReference ref = moduleConfig.customConfigSchemaReference();
                        // Using record accessors subject() and version()
                        Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.subject(), ref.version());
                        // Using record accessor schemaContent()
                        if (schemaDataOpt.isPresent() && schemaDataOpt.get().schemaContent() != null) {
                            schemaCacheForNewConfig.put(ref, schemaDataOpt.get().schemaContent());
                        } else {
                            LOG.warn("Schema content not found for reference {} during {}. Validation might fail for steps using this module.", ref, updateSource);
                        }
                    }
                }
            }
            LOG.debug("Fetched {} schema references for validation during {}.", schemaCacheForNewConfig.size(), updateSource);

            ValidationResult validationResult = configurationValidator.validate(
                newConfig,
                (schemaRef) -> Optional.ofNullable(schemaCacheForNewConfig.get(schemaRef))
            );

            if (validationResult.isValid()) {
                LOG.info("Configuration for cluster '{}' validated successfully ({}) . Updating cache and notifying listeners.", this.clusterName, updateSource);
                cachedConfigHolder.updateConfiguration(newConfig, schemaCacheForNewConfig);
                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfig, newConfig);
                publishEvent(event);
            } else {
                // Using record accessor errors()
                LOG.error("CRITICAL: New configuration for cluster '{}' ({}) failed validation. Errors: {}. Keeping previous configuration.",
                        this.clusterName, updateSource, validationResult.errors());
            }
        } catch (Exception e) {
            LOG.error("CRITICAL: Exception during processing of configuration update for cluster '{}' ({}): {}",
                    this.clusterName, updateSource, e.getMessage(), e);
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