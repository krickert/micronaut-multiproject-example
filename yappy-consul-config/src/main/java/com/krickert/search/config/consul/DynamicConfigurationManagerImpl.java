package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent; // Your NEW EVENT
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.scheduling.TaskExecutors; // For Micronaut managed executors
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Named; // For Micronaut managed executors
import jakarta.inject.Singleton;
import jakarta.inject.Provider; // For lazy injection if needed elsewhere, not strictly for this pattern but good to have
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService; // For deferred event publishing
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Singleton
public class DynamicConfigurationManagerImpl implements DynamicConfigurationManager {

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);
    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImpl.class);

    private final String defaultClusterName;
    private final ConsulConfigFetcher consulConfigFetcher;
    @Getter
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    private final ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;
    private final CopyOnWriteArrayList<Consumer<ClusterConfigUpdateEvent>> directListeners = new CopyOnWriteArrayList<>(); // Legacy listeners
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;
    private String effectiveClusterName;

    private final AtomicBoolean currentConfigIsStale = new AtomicBoolean(true);
    private final AtomicReference<String> currentConfigVersionIdentifier = new AtomicReference<>(null);

    // For deferring the initial event publication
    private final ExecutorService eventPublishingExecutor;
    private volatile PipelineClusterConfigChangeEvent initialLoadEventToPublish = null;

    @Inject
    public DynamicConfigurationManagerImpl(
            @Value("${app.config.cluster-name}") String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper,
            DefaultConfigurationSeeder seeder, // Ensures seeder's @PostConstruct runs first
            @Named(TaskExecutors.SCHEDULED) ExecutorService eventPublishingExecutor // Inject a Micronaut managed executor
    ) {
        LOG.info("DynamicConfigurationManagerImpl [{}] CONSTRUCTOR for cluster: {}", instanceId, clusterName);
        this.defaultClusterName = clusterName;
        this.effectiveClusterName = clusterName; // Initially same as default
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.objectMapper = objectMapper;
        this.eventPublishingExecutor = eventPublishingExecutor;
        // 'seeder' is injected to enforce initialization order, not necessarily used directly here.
    }

    @PostConstruct
    public void postConstructInitialize() {
        LOG.info("DynamicConfigurationManagerImpl [{}] @PostConstruct START for default cluster: {}", instanceId, defaultClusterName);
        initialize(this.defaultClusterName); // Initialize with the default/configured cluster name

        // If an "Initial Load" event was prepared by initialize(), publish it now,
        // after this @PostConstruct method has completed and the bean is fully constructed.
        if (initialLoadEventToPublish != null) {
            final PipelineClusterConfigChangeEvent eventToFire = initialLoadEventToPublish;
            initialLoadEventToPublish = null; // Clear it immediately to prevent re-publishing if @PostConstruct was somehow re-entered (which it shouldn't)

            LOG.info("DynamicConfigurationManagerImpl [{}] Submitting deferred 'Initial Load' event to executor for cluster '{}'", instanceId, eventToFire.clusterName());

            eventPublishingExecutor.execute(() -> {
                LOG.info("DynamicConfigurationManagerImpl [{}] Executor processing deferred 'Initial Load' event for cluster '{}'", instanceId, eventToFire.clusterName());
                publishMicronautEvent(eventToFire); // Actual publish on a different thread
            });
        }
        LOG.info("DynamicConfigurationManagerImpl [{}] @PostConstruct END for cluster: {}", instanceId, defaultClusterName);
    }

    @Override
    public void initialize(String clusterNameFromParam) {
        LOG.info("DynamicConfigurationManagerImpl [{}] initialize() method START for cluster: {}", this.instanceId, clusterNameFromParam);
        this.effectiveClusterName = clusterNameFromParam;

        if (!this.defaultClusterName.equals(clusterNameFromParam)) {
            LOG.info("DynamicConfigurationManagerImpl [{}] Initialize called with specific cluster name '{}', which differs from default startup cluster '{}'. This instance will now manage '{}'.",
                    this.instanceId, clusterNameFromParam, this.defaultClusterName, this.effectiveClusterName);
        }

        try {
            consulConfigFetcher.connect();
            LOG.info("DynamicConfigurationManagerImpl [{}]: Attempting initial configuration load for effective cluster: {}", this.instanceId, effectiveClusterName);
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);

            try {
                Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(effectiveClusterName);
                if (initialClusterConfigOpt.isPresent()) {
                    PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                    LOG.info("DynamicConfigurationManagerImpl [{}]: Initial configuration fetched for cluster '{}'. Processing...", this.instanceId, effectiveClusterName);
                    processConsulUpdate(WatchCallbackResult.success(initialConfig), "Initial Load");
                } else {
                    LOG.warn("DynamicConfigurationManagerImpl [{}]: No initial configuration found for cluster '{}'. Watch will pick up first appearance or deletion. Cache cleared, config marked stale.", this.instanceId, effectiveClusterName);
                    cachedConfigHolder.clearConfiguration();
                    currentConfigIsStale.set(true);
                    currentConfigVersionIdentifier.set(null);
                    // Use the corrected static factory method from WatchCallbackResult
                    processConsulUpdate(WatchCallbackResult.createAsDeleted(), "Initial Load - Not Found"); // ❗️ CORRECTED
                }
            } catch (Exception fetchEx) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Error during initial configuration fetch for cluster '{}': {}. Will still attempt to start watch.",
                        this.instanceId, effectiveClusterName, fetchEx.getMessage(), fetchEx);
                processConsulUpdate(WatchCallbackResult.failure(fetchEx), "Initial Load Fetch Error");
            }

            consulConfigFetcher.watchClusterConfig(effectiveClusterName, this::handleConsulWatchUpdate);
            LOG.info("DynamicConfigurationManagerImpl [{}]: Consul watch established for cluster configuration: {}", this.instanceId, effectiveClusterName);

        } catch (Exception e) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: CRITICAL: Failed to initialize DynamicConfigurationManager (connect or watch setup) for cluster '{}': {}",
                    this.instanceId, this.effectiveClusterName, e.getMessage(), e);
            cachedConfigHolder.clearConfiguration();
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);
            throw new ConfigurationManagerInitializationException(
                    "Failed to initialize Consul connection or watch for cluster " + this.effectiveClusterName, e);
        }
        LOG.info("DynamicConfigurationManagerImpl [{}] initialize() method END for cluster: {}", this.instanceId, clusterNameFromParam);
    }

    private void handleConsulWatchUpdate(WatchCallbackResult watchResult) {
        LOG.info("DynamicConfigurationManagerImpl [{}] handleConsulWatchUpdate received for cluster '{}'. Source: Consul Watch Update", this.instanceId, this.effectiveClusterName);
        processConsulUpdate(watchResult, "Consul Watch Update");
    }

    private synchronized void processConsulUpdate(WatchCallbackResult watchResult, String updateSource) {
        LOG.info("DynamicConfigurationManagerImpl [{}] processConsulUpdate from source: {} for cluster '{}'", this.instanceId, updateSource, this.effectiveClusterName);

        if (watchResult.hasError()) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: CRITICAL: Error received from Consul source '{}' for cluster '{}': {}. Keeping previous configuration. Marking as STALE.",
                    this.instanceId, updateSource, this.effectiveClusterName, watchResult.error().map(Throwable::getMessage).orElse("Unknown error"));
            watchResult.error().ifPresent(e -> LOG.debug("DynamicConfigurationManagerImpl [{}]: Consul source error details:", this.instanceId, e));
            currentConfigIsStale.set(true); // Mark stale, but don't clear version if we keep old config
            return;
        }

        if (watchResult.deleted()) {
            LOG.warn("DynamicConfigurationManagerImpl [{}]: PipelineClusterConfig for cluster '{}' indicated as deleted by source '{}'. Clearing local cache, marking STALE, and notifying listeners.",
                    this.instanceId, this.effectiveClusterName, updateSource);
            boolean wasPresent = cachedConfigHolder.getCurrentConfig().isPresent();
            cachedConfigHolder.clearConfiguration();
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);

            PipelineClusterConfigChangeEvent deletionEvent = PipelineClusterConfigChangeEvent.deletion(this.effectiveClusterName);
            if ("Initial Load".equals(updateSource) || "Initial Load - Not Found".equals(updateSource)) {
                LOG.info("DynamicConfigurationManagerImpl [{}]: QUEUING 'Deletion' event from source '{}' for deferred publication.", this.instanceId, updateSource);
                this.initialLoadEventToPublish = deletionEvent;
            } else {
                publishMicronautEvent(deletionEvent);
            }

            if (wasPresent) { // Legacy listeners might still expect this nuance
                notifyDirectListenersOfDeletion();
            } else if (!"Initial Load".equals(updateSource)){ // Avoid double notification if initial load was empty then deleted
                LOG.info("DynamicConfigurationManagerImpl [{}]: Cache was already empty for deletion event from source '{}' for cluster '{}'. No legacy deletion event published beyond Micronaut event.", this.instanceId, updateSource, this.effectiveClusterName);
            }
            return;
        }

        if (watchResult.config().isPresent()) {
            PipelineClusterConfig newConfig = watchResult.config().get();
            LOG.info("DynamicConfigurationManagerImpl [{}]: New configuration present from source '{}' for cluster '{}'. Processing validation...", this.instanceId, updateSource, this.effectiveClusterName);
            try {
                Map<SchemaReference, String> schemaCacheForNewConfig = new HashMap<>();
                boolean missingSchemaDetected = false;
                if (newConfig.pipelineModuleMap() != null && newConfig.pipelineModuleMap().availableModules() != null) {
                    for (PipelineModuleConfiguration moduleConfig : newConfig.pipelineModuleMap().availableModules().values()) {
                        if (moduleConfig.customConfigSchemaReference() != null) {
                            SchemaReference ref = moduleConfig.customConfigSchemaReference();
                            Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.subject(), ref.version());
                            if (schemaDataOpt.isPresent() && schemaDataOpt.get().schemaContent() != null) {
                                schemaCacheForNewConfig.put(ref, schemaDataOpt.get().schemaContent());
                            } else {
                                LOG.warn("DynamicConfigurationManagerImpl [{}]: Schema content not found for reference {} during processing for source '{}'. Validation will fail for steps using this module.",
                                        this.instanceId, ref, updateSource);
                                missingSchemaDetected = true;
                            }
                        }
                    }
                }
                LOG.debug("DynamicConfigurationManagerImpl [{}]: Fetched {} schema references for validation for source '{}'.", this.instanceId, schemaCacheForNewConfig.size(), updateSource);

                if (missingSchemaDetected) {
                    LOG.error("DynamicConfigurationManagerImpl [{}]: CRITICAL: New configuration for cluster '{}' from source '{}' has missing schemas. Marking as STALE. Not updating cache or publishing event.",
                            this.instanceId, this.effectiveClusterName, updateSource);
                    currentConfigIsStale.set(true);
                    if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
                    return;
                }

                ValidationResult validationResult = configurationValidator.validate(
                        newConfig,
                        (schemaRef) -> Optional.ofNullable(schemaCacheForNewConfig.get(schemaRef))
                );

                if (validationResult.isValid()) {
                    LOG.info("DynamicConfigurationManagerImpl [{}]: Configuration for cluster '{}' from source '{}' validated successfully. Updating cache and preparing to notify listeners.",
                            this.instanceId, this.effectiveClusterName, updateSource);
                    cachedConfigHolder.updateConfiguration(newConfig, schemaCacheForNewConfig);
                    currentConfigIsStale.set(false);
                    currentConfigVersionIdentifier.set(generateConfigVersion(newConfig));

                    PipelineClusterConfigChangeEvent updateEvent = new PipelineClusterConfigChangeEvent(this.effectiveClusterName, newConfig);
                    if ("Initial Load".equals(updateSource)) {
                        LOG.info("DynamicConfigurationManagerImpl [{}]: QUEUING 'Initial Load' update event for deferred publication.", this.instanceId);
                        this.initialLoadEventToPublish = updateEvent;
                    } else {
                        publishMicronautEvent(updateEvent);
                    }
                    notifyDirectListenersOfUpdate(newConfig); // Legacy direct listeners
                } else {
                    LOG.error("DynamicConfigurationManagerImpl [{}]: CRITICAL: New configuration for cluster '{}' from source '{}' failed validation. Marking as STALE. Not updating cache or publishing event. Errors: {}.",
                            this.instanceId, this.effectiveClusterName, updateSource, validationResult.errors());
                    currentConfigIsStale.set(true);
                    if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
                }
            } catch (Exception e) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: CRITICAL: Exception during processing of new configuration from source '{}' for cluster '{}': {}. Marking as STALE. Not updating cache or publishing event.",
                        this.instanceId, updateSource, this.effectiveClusterName, e.getMessage(), e);
                currentConfigIsStale.set(true);
                if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
            }
        } else {
            // This case implies watchResult.config() is empty, but it's not a deletion and not an error.
            // This could happen if the KVCache's initial state for a non-existent key is treated as an "update" with no data.
            LOG.warn("DynamicConfigurationManagerImpl [{}]: Received WatchCallbackResult from source '{}' for cluster '{}' with no config data, not marked as deleted, and no error. Marking as STALE. No event published.",
                    this.instanceId, updateSource, this.effectiveClusterName);
            currentConfigIsStale.set(true);
            // If it's an initial load and effectively nothing found, ensure cache is clear and notify listeners of this "empty" state.
            if ("Initial Load".equals(updateSource) || "Initial Load - Not Found".equals(updateSource)) {
                cachedConfigHolder.clearConfiguration();
                currentConfigVersionIdentifier.set(null);
                LOG.info("DynamicConfigurationManagerImpl [{}]: Queuing 'Initial Load - Not Found as Deletion' event for deferred publication.", this.instanceId);
                this.initialLoadEventToPublish = PipelineClusterConfigChangeEvent.deletion(this.effectiveClusterName);
            }
        }
    }

    private void publishMicronautEvent(PipelineClusterConfigChangeEvent event) {
        try {
            eventPublisher.publishEvent(event);
            LOG.info("DynamicConfigurationManagerImpl [{}] Published Micronaut PipelineClusterConfigChangeEvent for cluster '{}'. isDeletion: {}",
                    this.instanceId, event.clusterName(), event.isDeletion());
        } catch (Exception e) {
            LOG.error("DynamicConfigurationManagerImpl [{}] Error publishing Micronaut PipelineClusterConfigChangeEvent for cluster {}: {}",
                    this.instanceId, event.clusterName(), e.getMessage(), e);
            // This is where the StackOverflowError or BeanInstantiationException was happening
            // If this happens, it indicates a problem with event listener resolution or a listener's dependencies.
        }
    }

    // --- Methods for the legacy direct listener pattern ---
    private void notifyDirectListenersOfUpdate(PipelineClusterConfig newConfig) {
        // ClusterConfigUpdateEvent expects: Optional<PipelineClusterConfig> oldConfig, PipelineClusterConfig newConfig
        ClusterConfigUpdateEvent legacyEvent = new ClusterConfigUpdateEvent(cachedConfigHolder.getCurrentConfig(), newConfig); // ❗️ CORRECTED
        directListeners.forEach(listener -> {
            try {
                listener.accept(legacyEvent);
            } catch (Exception e) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Error invoking direct config update listener for cluster {}: {}", this.instanceId, this.effectiveClusterName, e.getMessage(), e);
            }
        });
        LOG.debug("DynamicConfigurationManagerImpl [{}]: Notified {} direct listeners of configuration update for cluster '{}'.", this.instanceId, directListeners.size(), this.effectiveClusterName);
    }

    private void notifyDirectListenersOfDeletion() {
        // Create an "empty" PipelineClusterConfig for the newConfig parameter, or however you define it.
        // Assuming clusterName is essential for even an "empty" config object.
        PipelineClusterConfig effectivelyEmptyConfig = PipelineClusterConfig.builder()
                .clusterName(this.effectiveClusterName)
                .pipelineGraphConfig(PipelineGraphConfig.builder().pipelines(Collections.emptyMap()).build())
                .pipelineModuleMap(PipelineModuleMap.builder().availableModules(Collections.emptyMap()).build())
                .allowedKafkaTopics(Collections.emptySet())
                .allowedGrpcServices(Collections.emptySet())
                .build();
        ClusterConfigUpdateEvent legacyEvent = new ClusterConfigUpdateEvent(Optional.empty(), effectivelyEmptyConfig); // ❗️ CORRECTED
        directListeners.forEach(listener -> {
            try {
                listener.accept(legacyEvent);
            } catch (Exception e) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Error invoking direct config deletion listener for cluster {}: {}", this.instanceId, this.effectiveClusterName, e.getMessage(), e);
            }
        });
        LOG.debug("DynamicConfigurationManagerImpl [{}]: Notified {} direct listeners of configuration deletion for cluster '{}'.", this.instanceId, directListeners.size(), this.effectiveClusterName);
    }

    // --- Public API Methods from DynamicConfigurationManager ---
    @Override
    public Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig() {
        return cachedConfigHolder.getCurrentConfig();
    }

    @Override
    public Optional<PipelineConfig> getPipelineConfig(String pipelineId) {
        return getCurrentPipelineClusterConfig().flatMap(clusterConfig ->
                Optional.ofNullable(clusterConfig.pipelineGraphConfig())
                        .flatMap(graph -> Optional.ofNullable(graph.pipelines()) // Check if pipelines map is null
                                .flatMap(pipelinesMap -> Optional.ofNullable(pipelinesMap.get(pipelineId))))
        );
    }

    @Override
    public Optional<String> getSchemaContent(SchemaReference schemaRef) {
        return cachedConfigHolder.getSchemaContent(schemaRef);
    }

    @Override
    public void registerConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        directListeners.add(listener);
        LOG.info("DynamicConfigurationManagerImpl [{}]: Registered direct listener. Total direct listeners: {}", this.instanceId, directListeners.size());
    }

    @Override
    public void unregisterConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        boolean removed = directListeners.remove(listener);
        if (removed) {
            LOG.info("DynamicConfigurationManagerImpl [{}]: Unregistered direct listener. Total direct listeners: {}", this.instanceId, directListeners.size());
        } else {
            LOG.warn("DynamicConfigurationManagerImpl [{}]: Attempted to unregister a direct listener that was not registered.", this.instanceId);
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        LOG.info("DynamicConfigurationManagerImpl [{}]: Shutting down for cluster: {}", this.instanceId, this.effectiveClusterName);
        if (consulConfigFetcher != null) {
            try {
                consulConfigFetcher.close();
            } catch (Exception e) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Error shutting down ConsulConfigFetcher: {}", this.instanceId, e.getMessage(), e);
            }
        }
        directListeners.clear();
        if (eventPublishingExecutor != null && !eventPublishingExecutor.isShutdown()) {
            LOG.info("DynamicConfigurationManagerImpl [{}]: Shutting down eventPublishingExecutor.", this.instanceId);
            eventPublishingExecutor.shutdown();
            try {
                if (!eventPublishingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    eventPublishingExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                eventPublishingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Helper Methods ---
    private String generateConfigVersion(PipelineClusterConfig config) {
        if (config == null) {
            return "null-config-" + System.currentTimeMillis();
        }
        try {
            String jsonConfig = objectMapper.writeValueAsString(config); // Use the injected objectMapper
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(jsonConfig.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException e) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Failed to serialize PipelineClusterConfig to JSON for version generation: {}", this.instanceId, e.getMessage());
            return "serialization-error-" + System.currentTimeMillis();
        } catch (NoSuchAlgorithmException e) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: MD5 algorithm not found for version generation: {}", this.instanceId, e.getMessage());
            return "hashing-algo-error-" + System.currentTimeMillis();
        }
    }

    private PipelineClusterConfig deepCopyConfig(PipelineClusterConfig config) {
        if (config == null) return null;
        try {
            String json = objectMapper.writeValueAsString(config);
            return objectMapper.readValue(json, PipelineClusterConfig.class);
        } catch (IOException e) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Failed to create deep copy of config: {}", this.instanceId, e.getMessage(), e);
            throw new RuntimeException("Failed to create deep copy of config", e);
        }
    }

    private boolean saveConfigToConsul(PipelineClusterConfig updatedConfig) {
        LOG.info("DynamicConfigurationManagerImpl [{}]: Attempting to save configuration to Consul for cluster '{}'", this.instanceId, updatedConfig.clusterName());
        try {
            Map<SchemaReference, String> schemaCacheForConfig = new HashMap<>();
            if (updatedConfig.pipelineModuleMap() != null && updatedConfig.pipelineModuleMap().availableModules() != null) {
                for (PipelineModuleConfiguration moduleConfig : updatedConfig.pipelineModuleMap().availableModules().values()) {
                    if (moduleConfig.customConfigSchemaReference() != null) {
                        SchemaReference ref = moduleConfig.customConfigSchemaReference();
                        Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.subject(), ref.version());
                        if (schemaDataOpt.isPresent() && schemaDataOpt.get().schemaContent() != null) {
                            schemaCacheForConfig.put(ref, schemaDataOpt.get().schemaContent());
                        } else {
                            LOG.warn("DynamicConfigurationManagerImpl [{}]: Schema content not found for reference {} during validation before saving. Validation may fail for steps using this module.", this.instanceId, ref);
                        }
                    }
                }
            }

            ValidationResult validationResult = configurationValidator.validate(
                    updatedConfig,
                    (schemaRef) -> Optional.ofNullable(schemaCacheForConfig.get(schemaRef))
            );

            if (!validationResult.isValid()) {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Cannot save configuration to Consul for cluster '{}' as it failed validation. Errors: {}",
                        this.instanceId, updatedConfig.clusterName(), validationResult.errors());
                return false;
            }

            String clusterNameKey = updatedConfig.clusterName(); // Use the name from the config object itself
            boolean success = Boolean.TRUE.equals(consulBusinessOperationsService.storeClusterConfiguration(clusterNameKey, updatedConfig).block());
            if (success) {
                LOG.info("DynamicConfigurationManagerImpl [{}]: Successfully saved updated configuration to Consul for cluster: {}. Watch should pick this up.", this.instanceId, clusterNameKey);
                return true;
            } else {
                LOG.error("DynamicConfigurationManagerImpl [{}]: Failed to save updated configuration to Consul for cluster: {}", this.instanceId, clusterNameKey);
                return false;
            }
        } catch (Exception e) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Error saving updated configuration to Consul for cluster {}: {}",
                    this.instanceId, updatedConfig.clusterName(), e.getMessage(), e);
            return false;
        }
    }

    // --- Methods that modify config and save it back to Consul ---
    // (These methods should use the effectiveClusterName or take clusterName as a parameter
    // if they are intended to modify the currently managed cluster's config)

    @Override
    public boolean addKafkaTopic(String newTopic) {
        LOG.info("DynamicConfigurationManagerImpl [{}]: Adding Kafka topic '{}' to allowed topics for cluster: {}", this.instanceId, newTopic, this.effectiveClusterName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Cannot add Kafka topic: No current configuration available for cluster: {}", this.instanceId, this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = currentConfigOpt.get(); // Already a deep copy if from cache, but be mindful if cache returns direct ref
        PipelineClusterConfig.PipelineClusterConfigBuilder updatedConfigBuilder = currentConfig.toBuilder(); // Assuming your model has toBuilder()

        Set<String> updatedTopics = new HashSet<>(currentConfig.allowedKafkaTopics() != null ? currentConfig.allowedKafkaTopics() : Collections.emptySet());
        if (updatedTopics.contains(newTopic)) {
            LOG.info("DynamicConfigurationManagerImpl [{}]: Kafka topic '{}' already exists in allowed topics for cluster: {}", this.instanceId, newTopic, this.effectiveClusterName);
            return true;
        }
        updatedTopics.add(newTopic);
        updatedConfigBuilder.allowedKafkaTopics(updatedTopics);

        return saveConfigToConsul(updatedConfigBuilder.build());
    }

    @Override
    public boolean updatePipelineStepToUseKafkaTopic(String pipelineName, String stepName,
                                                     String outputKey, String newTopic, String targetStepName) {
        LOG.info("DynamicConfigurationManagerImpl [{}]: Updating pipeline step '{}' in pipeline '{}' to use Kafka topic '{}' for output '{}' targeting '{}' in cluster '{}'",
                this.instanceId, stepName, pipelineName, newTopic, outputKey, targetStepName, this.effectiveClusterName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Cannot update pipeline step: No current configuration available for cluster: {}", this.instanceId, this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = currentConfigOpt.get(); // Get the actual config

        PipelineGraphConfig currentGraphConfig = currentConfig.pipelineGraphConfig();
        if (currentGraphConfig == null || currentGraphConfig.pipelines() == null ||
                !currentGraphConfig.pipelines().containsKey(pipelineName)) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Pipeline '{}' not found in cluster '{}'.", this.instanceId, pipelineName, this.effectiveClusterName);
            return false;
        }

        PipelineConfig pipelineToUpdate = currentGraphConfig.pipelines().get(pipelineName);
        if (pipelineToUpdate.pipelineSteps() == null || !pipelineToUpdate.pipelineSteps().containsKey(stepName)) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Step '{}' not found in pipeline '{}' of cluster '{}'.", this.instanceId, stepName, pipelineName, this.effectiveClusterName);
            return false;
        }

        PipelineStepConfig stepToUpdate = pipelineToUpdate.pipelineSteps().get(stepName);

        // Create new OutputTarget
        Map<String, PipelineStepConfig.OutputTarget> newOutputs = new HashMap<>(stepToUpdate.outputs() != null ? stepToUpdate.outputs() : Collections.emptyMap());
        KafkaTransportConfig kafkaTransport = KafkaTransportConfig.builder().topic(newTopic).kafkaProducerProperties(Map.of("compression.type", "snappy")).build();
        PipelineStepConfig.OutputTarget newOutputTarget = PipelineStepConfig.OutputTarget.builder()
                .targetStepName(targetStepName)
                .transportType(TransportType.KAFKA)
                .kafkaTransport(kafkaTransport)
                .build();
        newOutputs.put(outputKey, newOutputTarget);

        // Create new StepConfig using toBuilder()
        PipelineStepConfig updatedStep = stepToUpdate.toBuilder()
                .outputs(newOutputs)
                .build();

        // Create new map of steps for the pipeline
        Map<String, PipelineStepConfig> updatedPipelineSteps = new HashMap<>(pipelineToUpdate.pipelineSteps());
        updatedPipelineSteps.put(stepName, updatedStep);

        // Create new PipelineConfig using toBuilder()
        PipelineConfig updatedPipeline = pipelineToUpdate.toBuilder()
                .pipelineSteps(updatedPipelineSteps)
                .build();

        // Create new map of pipelines for the graph
        Map<String, PipelineConfig> updatedPipelinesMap = new HashMap<>(currentGraphConfig.pipelines());
        updatedPipelinesMap.put(pipelineName, updatedPipeline);

        // Create new PipelineGraphConfig using toBuilder()
        PipelineGraphConfig updatedGraphConfig = currentGraphConfig.toBuilder()
                .pipelines(updatedPipelinesMap)
                .build();

        // Create final updated PipelineClusterConfig using toBuilder()
        PipelineClusterConfig finalConfig = currentConfig.toBuilder()
                .pipelineGraphConfig(updatedGraphConfig)
                .build();

        return saveConfigToConsul(finalConfig);
    }

    @Override
    public boolean deleteServiceAndUpdateConnections(String serviceName) {
        LOG.info("DynamicConfigurationManagerImpl [{}]: Deleting service '{}' and updating connections for cluster: {}", this.instanceId, serviceName, this.effectiveClusterName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("DynamicConfigurationManagerImpl [{}]: Cannot delete service: No current configuration available for cluster: {}", this.instanceId, this.effectiveClusterName);
            return false;
        }
        PipelineClusterConfig currentConfig = currentConfigOpt.get();
        PipelineClusterConfig.PipelineClusterConfigBuilder clusterConfigBuilder = currentConfig.toBuilder();

        // Update allowedGrpcServices
        Set<String> updatedAllowedServices = (currentConfig.allowedGrpcServices() != null)
                ? new HashSet<>(currentConfig.allowedGrpcServices())
                : new HashSet<>();
        if (!updatedAllowedServices.remove(serviceName)) {
            LOG.warn("DynamicConfigurationManagerImpl [{}]: Service '{}' was not in allowedGrpcServices list for cluster '{}'.", this.instanceId, serviceName, this.effectiveClusterName);
        }
        clusterConfigBuilder.allowedGrpcServices(updatedAllowedServices);

        // Update PipelineModuleMap
        Map<String, PipelineModuleConfiguration> updatedModules =
                (currentConfig.pipelineModuleMap() != null && currentConfig.pipelineModuleMap().availableModules() != null)
                        ? new HashMap<>(currentConfig.pipelineModuleMap().availableModules())
                        : new HashMap<>();
        if (updatedModules.remove(serviceName) == null) {
            LOG.warn("DynamicConfigurationManagerImpl [{}]: Service '{}' not found in pipelineModuleMap for cluster '{}'.", this.instanceId, serviceName, this.effectiveClusterName);
        }
        clusterConfigBuilder.pipelineModuleMap(PipelineModuleMap.builder().availableModules(updatedModules).build());

        // Update PipelineGraphConfig
        if (currentConfig.pipelineGraphConfig() != null && currentConfig.pipelineGraphConfig().pipelines() != null) {
            Map<String, PipelineConfig> updatedPipelinesMap = new HashMap<>();
            for (Map.Entry<String, PipelineConfig> pipelineEntry : currentConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pName = pipelineEntry.getKey();
                PipelineConfig pConfig = pipelineEntry.getValue();
                PipelineConfig.PipelineConfigBuilder pipelineBuilder = pConfig.toBuilder();
                Map<String, PipelineStepConfig> updatedPipelineSteps = new HashMap<>();
                boolean pipelineStepsChanged = false;

                if (pConfig.pipelineSteps() != null) {
                    for (Map.Entry<String, PipelineStepConfig> stepEntry : pConfig.pipelineSteps().entrySet()) {
                        String sName = stepEntry.getKey();
                        PipelineStepConfig step = stepEntry.getValue();

                        if (step.processorInfo() != null && serviceName.equals(step.processorInfo().grpcServiceName())) {
                            LOG.info("DynamicConfigurationManagerImpl [{}]: Removing step '{}' from pipeline '{}' as it uses deleted service '{}'.", this.instanceId, sName, pName, serviceName);
                            pipelineStepsChanged = true;
                            continue;
                        }

                        Map<String, PipelineStepConfig.OutputTarget> currentOutputs = step.outputs() != null ? step.outputs() : Collections.emptyMap();
                        Map<String, PipelineStepConfig.OutputTarget> newOutputs = new HashMap<>();
                        boolean outputsChangedForThisStep = false;

                        for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : currentOutputs.entrySet()) {
                            PipelineStepConfig.OutputTarget output = outputEntry.getValue();
                            boolean removeOutput = false;

                            if (output.transportType() == TransportType.GRPC && output.grpcTransport() != null &&
                                    serviceName.equals(output.grpcTransport().serviceName())) {
                                removeOutput = true;
                            } else {
                                // Basic check if targetStepName itself (if a gRPC service module) is the one being deleted
                                String targetStepFullName = output.targetStepName();
                                if (targetStepFullName != null && updatedModules.get(targetStepFullName) == null && currentConfig.pipelineModuleMap().availableModules().containsKey(targetStepFullName)) {
                                    // If target step was a module and is now removed from updatedModules
                                    PipelineModuleConfiguration targetModule = currentConfig.pipelineModuleMap().availableModules().get(targetStepFullName);
                                    if (targetModule != null && serviceName.equals(targetStepFullName) ) { // Check if it's the service being deleted
                                        removeOutput = true;
                                    }
                                }
                                // More complex: Check if targetStepName refers to a step that uses the deleted service
                                // This requires iterating through the graph which can be complex.
                                // For now, only direct references or target steps that *are* the service are removed.
                            }

                            if (removeOutput) {
                                LOG.info("DynamicConfigurationManagerImpl [{}]: Removing output '{}' from step '{}' in pipeline '{}' as it targets deleted service '{}' or a step using it.",
                                        this.instanceId, outputEntry.getKey(), sName, pName, serviceName);
                                outputsChangedForThisStep = true;
                            } else {
                                newOutputs.put(outputEntry.getKey(), output);
                            }
                        }
                        if (outputsChangedForThisStep) {
                            updatedPipelineSteps.put(sName, step.toBuilder().outputs(newOutputs).build());
                            pipelineStepsChanged = true;
                        } else {
                            updatedPipelineSteps.put(sName, step);
                        }
                    }
                }
                if (pipelineStepsChanged) {
                    updatedPipelinesMap.put(pName, pipelineBuilder.pipelineSteps(updatedPipelineSteps).build());
                } else {
                    updatedPipelinesMap.put(pName, pConfig); // No change to this pipeline's steps
                }
            }
            clusterConfigBuilder.pipelineGraphConfig(currentConfig.pipelineGraphConfig().toBuilder().pipelines(updatedPipelinesMap).build());
        }
        return saveConfigToConsul(clusterConfigBuilder.build());
    }

    @Override
    public boolean isCurrentConfigStale() {
        return currentConfigIsStale.get();
    }

    @Override
    public Optional<String> getCurrentConfigVersionIdentifier() {
        return Optional.ofNullable(currentConfigVersionIdentifier.get());
    }
}