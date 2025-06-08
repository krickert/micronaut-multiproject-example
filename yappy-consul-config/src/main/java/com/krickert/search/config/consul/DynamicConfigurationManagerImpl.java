package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
// import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent; // Old event
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent; // NEW EVENT
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Singleton
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class DynamicConfigurationManagerImpl implements DynamicConfigurationManager {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImpl.class);

    private final String defaultClusterName;
    private final ConsulConfigFetcher consulConfigFetcher;
    @Getter
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    // Use the new event type
    private final ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher;
    // This listener list is for a different, direct listener pattern. We'll keep it for now
    // but the primary inter-module communication will be via Micronaut events.
    private final CopyOnWriteArrayList<Consumer<ClusterConfigUpdateEvent>> directListeners = new CopyOnWriteArrayList<>();
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;
    private String effectiveClusterName;

    private final AtomicBoolean currentConfigIsStale = new AtomicBoolean(true);
    private final AtomicReference<String> currentConfigVersionIdentifier = new AtomicReference<>(null);

    public DynamicConfigurationManagerImpl(
            @Value("${app.config.cluster-name}") String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<PipelineClusterConfigChangeEvent> eventPublisher, // Correct event type
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper
    ) {
        this.defaultClusterName = clusterName;
        this.effectiveClusterName = clusterName;
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        this.consulBusinessOperationsService = consulBusinessOperationsService;
        this.objectMapper = objectMapper;
        LOG.info("DynamicConfigurationManagerImpl created for cluster: {}", clusterName);
    }

    @PostConstruct
    public void postConstructInitialize() {
        LOG.info("Initializing DynamicConfigurationManager for cluster: {}", defaultClusterName);
        initialize(this.defaultClusterName);
    }

    @Override
    public void initialize(String clusterNameFromParam) {
        this.effectiveClusterName = clusterNameFromParam;
        if (!this.defaultClusterName.equals(clusterNameFromParam)) {
            LOG.info("Initialize called with cluster name '{}', which differs from default '{}'. Using provided name.",
                    clusterNameFromParam, this.defaultClusterName);
        }

        try {
            consulConfigFetcher.connect();
            LOG.info("Attempting initial configuration load for cluster: {}", effectiveClusterName);
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);

            try {
                Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(effectiveClusterName);
                if (initialClusterConfigOpt.isPresent()) {
                    PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                    LOG.info("Initial configuration fetched for cluster '{}'. Processing...", effectiveClusterName);
                    processConsulUpdate(WatchCallbackResult.success(initialConfig), "Initial Load");
                } else {
                    LOG.warn("No initial configuration found for cluster '{}'. Watch will pick up first appearance or deletion.", effectiveClusterName);
                    cachedConfigHolder.clearConfiguration();
                    currentConfigIsStale.set(true);
                    currentConfigVersionIdentifier.set(null);
                    LOG.info("Cache cleared, config marked stale due to no initial configuration for cluster '{}'.", effectiveClusterName);
                    // Publish deletion event if there was an old config, or an "empty" update
                    // For initial load, if nothing is found, it's like a deletion from a non-existent state.
                    // We might not need to publish an event here unless a previous in-memory state existed.
                    // However, to be consistent, if the state changes from "something" (even if unknown) to "nothing",
                    // an event could be useful. For now, let's only publish on explicit deletion or valid update.
                }
            } catch (Exception fetchEx) {
                LOG.error("Error during initial configuration fetch for cluster '{}': {}. Will still attempt to start watch.",
                        effectiveClusterName, fetchEx.getMessage(), fetchEx);
                // processConsulUpdate will handle marking as stale
                processConsulUpdate(WatchCallbackResult.failure(fetchEx), "Initial Load Fetch Error");
            }

            consulConfigFetcher.watchClusterConfig(effectiveClusterName, this::handleConsulWatchUpdate);
            LOG.info("Consul watch established for cluster configuration: {}", effectiveClusterName);

        } catch (Exception e) {
            LOG.error("CRITICAL: Failed to initialize DynamicConfigurationManager (connect or watch setup) for cluster '{}': {}",
                    this.effectiveClusterName, e.getMessage(), e);
            cachedConfigHolder.clearConfiguration();
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);
            LOG.info("Cache cleared, config marked stale due to connection or watch setup failure for cluster '{}'.", effectiveClusterName);
            // Consider if an event should be published here indicating a failure to initialize.
            throw new ConfigurationManagerInitializationException(
                    "Failed to initialize Consul connection or watch for cluster " + this.effectiveClusterName, e);
        }
    }

    private void handleConsulWatchUpdate(WatchCallbackResult watchResult) {
        processConsulUpdate(watchResult, "Consul Watch Update");
    }

    private void processConsulUpdate(WatchCallbackResult watchResult, String updateSource) {
        // Optional<PipelineClusterConfig> oldConfigForEvent = cachedConfigHolder.getCurrentConfig(); // We don't need old config for the new event type

        if (watchResult.hasError()) {
            LOG.error("CRITICAL: Error received from Consul source '{}' for cluster '{}': {}. Keeping previous configuration. Marking as STALE.",
                    updateSource, this.effectiveClusterName, watchResult.error().map(Throwable::getMessage).orElse("Unknown error"));
            watchResult.error().ifPresent(e -> LOG.debug("Consul source error details:", e));
            currentConfigIsStale.set(true);
            // No change to config, so no event published. Version identifier remains.
            return;
        }

        if (watchResult.deleted()) {
            LOG.warn("PipelineClusterConfig for cluster '{}' indicated as deleted by source '{}'. Clearing local cache, marking STALE, and notifying listeners.",
                    this.effectiveClusterName, updateSource);
            boolean wasPresent = cachedConfigHolder.getCurrentConfig().isPresent();
            cachedConfigHolder.clearConfiguration();
            currentConfigIsStale.set(true);
            currentConfigVersionIdentifier.set(null);

            if (wasPresent) { // Only publish deletion if there was something to delete
                publishMicronautEvent(PipelineClusterConfigChangeEvent.deletion(this.effectiveClusterName));
            } else {
                LOG.info("Cache was already empty for deletion event from source '{}' for cluster '{}'. No deletion event published.", updateSource, this.effectiveClusterName);
            }
            // Also notify direct listeners if any (legacy pattern)
            notifyDirectListenersOfDeletion();
            return;
        }

        if (watchResult.config().isPresent()) {
            PipelineClusterConfig newConfig = watchResult.config().get();
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
                                LOG.warn("Schema content not found for reference {} during processing for source '{}'. Validation will fail for steps using this module.",
                                        ref, updateSource);
                                missingSchemaDetected = true;
                            }
                        }
                    }
                }
                LOG.debug("Fetched {} schema references for validation for source '{}'.", schemaCacheForNewConfig.size(), updateSource);

                if (missingSchemaDetected) {
                    LOG.error("CRITICAL: New configuration for cluster '{}' from source '{}' has missing schemas. Marking as STALE. Not updating cache or publishing event.",
                            this.effectiveClusterName, updateSource);
                    currentConfigIsStale.set(true);
                    // If it's an initial load and schemas are missing, clear any potentially fetched (but unvalidated) config.
                    if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
                    return;
                }

                ValidationResult validationResult = configurationValidator.validate(
                        newConfig,
                        (schemaRef) -> Optional.ofNullable(schemaCacheForNewConfig.get(schemaRef))
                );

                if (validationResult.isValid()) {
                    LOG.info("Configuration for cluster '{}' from source '{}' validated successfully. Updating cache and notifying listeners.",
                            this.effectiveClusterName, updateSource);
                    cachedConfigHolder.updateConfiguration(newConfig, schemaCacheForNewConfig);
                    currentConfigIsStale.set(false);
                    currentConfigVersionIdentifier.set(generateConfigVersion(newConfig));

                    publishMicronautEvent(new PipelineClusterConfigChangeEvent(this.effectiveClusterName, newConfig));
                    // Also notify direct listeners if any (legacy pattern)
                    notifyDirectListenersOfUpdate(newConfig);

                } else {
                    LOG.error("CRITICAL: New configuration for cluster '{}' from source '{}' failed validation. Marking as STALE. Not updating cache or publishing event. Errors: {}.",
                            this.effectiveClusterName, updateSource, validationResult.errors());
                    currentConfigIsStale.set(true);
                    // If it's an initial load and validation fails, clear any potentially fetched (but unvalidated) config.
                    if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
                }
            } catch (Exception e) {
                LOG.error("CRITICAL: Exception during processing of new configuration from source '{}' for cluster '{}': {}. Marking as STALE. Not updating cache or publishing event.",
                        updateSource, this.effectiveClusterName, e.getMessage(), e);
                currentConfigIsStale.set(true);
                if (updateSource.equals("Initial Load")) cachedConfigHolder.clearConfiguration();
            }
        } else {
            LOG.warn("Received ambiguous WatchCallbackResult (no config, no error, not deleted) from source '{}' for cluster '{}'. Marking as STALE. No event published.",
                    updateSource, this.effectiveClusterName);
            currentConfigIsStale.set(true);
        }
    }

    private String generateConfigVersion(PipelineClusterConfig config) {
        if (config == null) {
            return "null-config-" + System.currentTimeMillis(); // Add timestamp to differentiate nulls over time
        }
        try {
            String jsonConfig = objectMapper.writeValueAsString(config);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(jsonConfig.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize PipelineClusterConfig to JSON for version generation: {}", e.getMessage());
            return "serialization-error-" + System.currentTimeMillis();
        } catch (NoSuchAlgorithmException e) {
            LOG.error("MD5 algorithm not found for version generation: {}", e.getMessage());
            return "hashing-algo-error-" + System.currentTimeMillis();
        }
    }

    // Renamed for clarity
    private void publishMicronautEvent(PipelineClusterConfigChangeEvent event) {
        try {
            eventPublisher.publishEvent(event);
            LOG.info("Published Micronaut PipelineClusterConfigChangeEvent for cluster '{}'. isDeletion: {}",
                    event.clusterName(), event.isDeletion());
        } catch (Exception e) {
            LOG.error("Error publishing Micronaut PipelineClusterConfigChangeEvent for cluster {}: {}",
                    event.clusterName(), e.getMessage(), e);
        }
    }

    // --- Methods for the legacy direct listener pattern ---
    private void notifyDirectListenersOfUpdate(PipelineClusterConfig newConfig) {
        // This uses the old ClusterConfigUpdateEvent for compatibility with existing direct listeners
        ClusterConfigUpdateEvent legacyEvent = new ClusterConfigUpdateEvent(cachedConfigHolder.getCurrentConfig(), newConfig);
        directListeners.forEach(listener -> {
            try {
                listener.accept(legacyEvent);
            } catch (Exception e) {
                LOG.error("Error invoking direct config update listener for cluster {}: {}", this.effectiveClusterName, e.getMessage(), e);
            }
        });
        LOG.debug("Notified {} direct listeners of configuration update for cluster '{}'.", directListeners.size(), this.effectiveClusterName);
    }

    private void notifyDirectListenersOfDeletion() {
        // This uses the old ClusterConfigUpdateEvent for compatibility
        PipelineClusterConfig effectivelyEmptyConfig = new PipelineClusterConfig(this.effectiveClusterName, null, null, null, null, null);
        ClusterConfigUpdateEvent legacyEvent = new ClusterConfigUpdateEvent(Optional.empty(), effectivelyEmptyConfig); // oldConfig is empty as it was just cleared
        directListeners.forEach(listener -> {
            try {
                listener.accept(legacyEvent);
            } catch (Exception e) {
                LOG.error("Error invoking direct config deletion listener for cluster {}: {}", this.effectiveClusterName, e.getMessage(), e);
            }
        });
        LOG.debug("Notified {} direct listeners of configuration deletion for cluster '{}'.", directListeners.size(), this.effectiveClusterName);
    }
    // --- End of methods for legacy direct listener pattern ---


    @Override
    public Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig() {
        return cachedConfigHolder.getCurrentConfig();
    }

    @Override
    public Optional<PipelineConfig> getPipelineConfig(String pipelineId) {
        return getCurrentPipelineClusterConfig().flatMap(clusterConfig ->
                Optional.ofNullable(clusterConfig.pipelineGraphConfig())
                        .flatMap(graph -> Optional.ofNullable(graph.getPipelineConfig(pipelineId)))
        );
    }

    @Override
    public Optional<String> getSchemaContent(SchemaReference schemaRef) {
        return cachedConfigHolder.getSchemaContent(schemaRef);
    }

    @Override
    public void registerConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        directListeners.add(listener);
        LOG.info("Registered direct listener. Total direct listeners: {}", directListeners.size());
    }

    @Override
    public void unregisterConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener) {
        boolean removed = directListeners.remove(listener);
        if (removed) {
            LOG.info("Unregistered direct listener. Total direct listeners: {}", directListeners.size());
        } else {
            LOG.warn("Attempted to unregister a direct listener that was not registered.");
        }
    }

    @PreDestroy
    @Override
    public void shutdown() {
        LOG.info("Shutting down DynamicConfigurationManager for cluster: {}", this.effectiveClusterName);
        if (consulConfigFetcher != null) {
            try {
                consulConfigFetcher.close();
            } catch (Exception e) {
                LOG.error("Error shutting down ConsulConfigFetcher: {}", e.getMessage(), e);
            }
        }
        directListeners.clear();
    }

    private PipelineClusterConfig deepCopyConfig(PipelineClusterConfig config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            return objectMapper.readValue(json, PipelineClusterConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create deep copy of config", e);
        }
    }

    private boolean saveConfigToConsul(PipelineClusterConfig updatedConfig) {
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
                            LOG.warn("Schema content not found for reference {} during validation before saving. Validation may fail for steps using this module.", ref);
                        }
                    }
                }
            }

            ValidationResult validationResult = configurationValidator.validate(
                    updatedConfig,
                    (schemaRef) -> Optional.ofNullable(schemaCacheForConfig.get(schemaRef))
            );

            if (!validationResult.isValid()) {
                LOG.error("Cannot save configuration to Consul for cluster '{}' as it failed validation. Errors: {}",
                        updatedConfig.clusterName(), validationResult.errors());
                return false;
            }

            String clusterName = updatedConfig.clusterName();
            boolean success = Boolean.TRUE.equals(consulBusinessOperationsService.storeClusterConfiguration(clusterName, updatedConfig).block());
            if (success) {
                LOG.info("Successfully saved updated configuration to Consul for cluster: {}", updatedConfig.clusterName());
                // The watch will pick up the change and trigger processConsulUpdate,
                // which will then update staleness, version, and publish events.
                return true;
            } else {
                LOG.error("Failed to save updated configuration to Consul for cluster: {}", updatedConfig.clusterName());
                return false;
            }
        } catch (Exception e) {
            LOG.error("Error saving updated configuration to Consul for cluster {}: {}",
                    updatedConfig.clusterName(), e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean addKafkaTopic(String newTopic) {
        LOG.info("Adding Kafka topic '{}' to allowed topics for cluster: {}", newTopic, this.effectiveClusterName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("Cannot add Kafka topic: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = currentConfigOpt.get();
        // Ensure allowedKafkaTopics is not null before creating a HashSet from it
        Set<String> currentAllowedTopics = currentConfig.allowedKafkaTopics() != null ? currentConfig.allowedKafkaTopics() : Collections.emptySet();
        Set<String> updatedTopics = new HashSet<>(currentAllowedTopics);

        if (updatedTopics.contains(newTopic)) {
            LOG.info("Kafka topic '{}' already exists in allowed topics for cluster: {}", newTopic, this.effectiveClusterName);
            return true; // Or false if "already exists" is not considered a successful addition of a *new* topic
        }
        updatedTopics.add(newTopic);

        PipelineClusterConfig updatedConfig = new PipelineClusterConfig(
                currentConfig.clusterName(),
                currentConfig.pipelineGraphConfig(),
                currentConfig.pipelineModuleMap(),
                currentConfig.defaultPipelineName(),
                updatedTopics,
                currentConfig.allowedGrpcServices()
        );
        return saveConfigToConsul(updatedConfig);
    }

    @Override
    public boolean updatePipelineStepToUseKafkaTopic(String pipelineName, String stepName,
                                                     String outputKey, String newTopic, String targetStepName) {
        LOG.info("Updating pipeline step '{}' in pipeline '{}' to use Kafka topic '{}' for output '{}' targeting '{}'",
                stepName, pipelineName, newTopic, outputKey, targetStepName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("Cannot update pipeline step: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = deepCopyConfig(currentConfigOpt.get());
        PipelineGraphConfig graph = currentConfig.pipelineGraphConfig();
        if (graph == null || graph.pipelines() == null || !graph.pipelines().containsKey(pipelineName)) {
            LOG.error("Pipeline '{}' not found.", pipelineName);
            return false;
        }

        PipelineConfig pipeline = graph.pipelines().get(pipelineName);
        if (pipeline.pipelineSteps() == null || !pipeline.pipelineSteps().containsKey(stepName)) {
            LOG.error("Step '{}' not found in pipeline '{}'.", stepName, pipelineName);
            return false;
        }

        PipelineStepConfig step = pipeline.pipelineSteps().get(stepName);
        Map<String, PipelineStepConfig.OutputTarget> newOutputs = new HashMap<>(step.outputs() != null ? step.outputs() : Collections.emptyMap());
        KafkaTransportConfig kafkaTransport = new KafkaTransportConfig(newTopic, Map.of("compression.type", "snappy")); // Example properties
        PipelineStepConfig.OutputTarget newOutputTarget = new PipelineStepConfig.OutputTarget(targetStepName, TransportType.KAFKA, null, kafkaTransport);
        newOutputs.put(outputKey, newOutputTarget);

        PipelineStepConfig updatedStep = new PipelineStepConfig(
                step.stepName(), step.stepType(), step.description(), step.customConfigSchemaId(),
                step.customConfig(), step.kafkaInputs(), newOutputs, step.maxRetries(),
                step.retryBackoffMs(), step.maxRetryBackoffMs(), step.retryBackoffMultiplier(),
                step.stepTimeoutMs(), step.processorInfo()
        );

        Map<String, PipelineStepConfig> newSteps = new HashMap<>(pipeline.pipelineSteps());
        newSteps.put(stepName, updatedStep);
        PipelineConfig updatedPipeline = new PipelineConfig(pipeline.name(), newSteps);

        Map<String, PipelineConfig> newPipelinesMap = new HashMap<>(graph.pipelines());
        newPipelinesMap.put(pipelineName, updatedPipeline);
        PipelineGraphConfig newGraph = new PipelineGraphConfig(newPipelinesMap);

        PipelineClusterConfig finalConfig = new PipelineClusterConfig(
                currentConfig.clusterName(), newGraph, currentConfig.pipelineModuleMap(),
                currentConfig.defaultPipelineName(), currentConfig.allowedKafkaTopics(),
                currentConfig.allowedGrpcServices()
        );
        return saveConfigToConsul(finalConfig);
    }

    @Override
    public boolean deleteServiceAndUpdateConnections(String serviceName) {
        LOG.info("Deleting service '{}' and updating all connections to/from it for cluster: {}", serviceName, this.effectiveClusterName);
        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (currentConfigOpt.isEmpty()) {
            LOG.error("Cannot delete service: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = deepCopyConfig(currentConfigOpt.get());

        Set<String> updatedAllowedServices = (currentConfig.allowedGrpcServices() != null)
                ? new HashSet<>(currentConfig.allowedGrpcServices())
                : new HashSet<>();
        boolean serviceWasAllowed = updatedAllowedServices.remove(serviceName);
        if (!serviceWasAllowed) {
            LOG.warn("Service '{}' was not in the allowedGrpcServices list.", serviceName);
        }

        Map<String, PipelineModuleConfiguration> currentAvailableModules = (currentConfig.pipelineModuleMap() != null && currentConfig.pipelineModuleMap().availableModules() != null)
                ? currentConfig.pipelineModuleMap().availableModules()
                : Collections.emptyMap();
        Map<String, PipelineModuleConfiguration> updatedModules = new HashMap<>(currentAvailableModules);

        PipelineModuleConfiguration removedModule = updatedModules.remove(serviceName);
        if (removedModule == null) {
            LOG.warn("Service '{}' was not found in the pipelineModuleMap.", serviceName);
        }
        PipelineModuleMap newModuleMap = new PipelineModuleMap(updatedModules);

        Map<String, PipelineConfig> newPipelinesMap = new HashMap<>();
        if (currentConfig.pipelineGraphConfig() != null && currentConfig.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : currentConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pName = pipelineEntry.getKey();
                PipelineConfig pConfig = pipelineEntry.getValue();
                Map<String, PipelineStepConfig> newSteps = new HashMap<>();
                // boolean pipelineChanged = false; // Not strictly needed if we always create a new PipelineConfig

                if (pConfig.pipelineSteps() != null) {
                    for (Map.Entry<String, PipelineStepConfig> stepEntry : pConfig.pipelineSteps().entrySet()) {
                        String sName = stepEntry.getKey();
                        PipelineStepConfig step = stepEntry.getValue();

                        if (step.processorInfo() != null && serviceName.equals(step.processorInfo().grpcServiceName())) {
                            LOG.info("Removing step '{}' from pipeline '{}' as it uses deleted service '{}'.", sName, pName, serviceName);
                            // pipelineChanged = true; // Mark change
                            continue; // Skip adding this step
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
                                String targetStepFullName = output.targetStepName();
                                if (targetStepFullName != null) {
                                    String targetPipelineName = pName;
                                    String targetStepNameOnly = targetStepFullName;
                                    if (targetStepFullName.contains(".")) {
                                        String[] parts = targetStepFullName.split("\\.", 2);
                                        targetPipelineName = parts[0];
                                        targetStepNameOnly = parts[1];
                                    }

                                    PipelineConfig targetPipeline = currentConfig.pipelineGraphConfig().pipelines().get(targetPipelineName);
                                    if (targetPipeline != null && targetPipeline.pipelineSteps() != null) {
                                        PipelineStepConfig targetStep = targetPipeline.pipelineSteps().get(targetStepNameOnly);
                                        if (targetStep != null && targetStep.processorInfo() != null &&
                                                serviceName.equals(targetStep.processorInfo().grpcServiceName())) {
                                            removeOutput = true;
                                        }
                                    }
                                }
                            }

                            if (removeOutput) {
                                LOG.info("Removing output '{}' from step '{}' in pipeline '{}' as it targets deleted service '{}' or a step using it.",
                                        outputEntry.getKey(), sName, pName, serviceName);
                                outputsChangedForThisStep = true;
                                // pipelineChanged = true; // Mark change
                            } else {
                                newOutputs.put(outputEntry.getKey(), output);
                            }
                        }
                        if (outputsChangedForThisStep) {
                            step = new PipelineStepConfig(
                                    step.stepName(), step.stepType(), step.description(), step.customConfigSchemaId(),
                                    step.customConfig(), step.kafkaInputs(), newOutputs, step.maxRetries(),
                                    step.retryBackoffMs(), step.maxRetryBackoffMs(), step.retryBackoffMultiplier(),
                                    step.stepTimeoutMs(), step.processorInfo()
                            );
                        }
                        newSteps.put(sName, step);
                    }
                }
                newPipelinesMap.put(pName, new PipelineConfig(pName, newSteps));
            }
        }
        PipelineGraphConfig newGraph = new PipelineGraphConfig(newPipelinesMap);

        PipelineClusterConfig finalConfig = new PipelineClusterConfig(
                currentConfig.clusterName(), newGraph, newModuleMap,
                currentConfig.defaultPipelineName(), currentConfig.allowedKafkaTopics(),
                updatedAllowedServices
        );
        return saveConfigToConsul(finalConfig);
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