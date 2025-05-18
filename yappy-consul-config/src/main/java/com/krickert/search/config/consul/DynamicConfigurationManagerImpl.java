package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.consul.exception.ConfigurationManagerInitializationException;
import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.search.config.consul.service.ConsulKvService;
import com.krickert.search.config.pipeline.model.*;

import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Singleton
public class DynamicConfigurationManagerImpl implements DynamicConfigurationManager {

    private static final Logger LOG = LoggerFactory.getLogger(DynamicConfigurationManagerImpl.class);

    private final String defaultClusterName;
    private String effectiveClusterName;
    private final ConsulConfigFetcher consulConfigFetcher;
    private final ConfigurationValidator configurationValidator;
    private final CachedConfigHolder cachedConfigHolder;
    private final ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher;
    private final CopyOnWriteArrayList<Consumer<ClusterConfigUpdateEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConsulKvService consulKvService;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    private final ObjectMapper objectMapper;

    public DynamicConfigurationManagerImpl(
            @Value("${app.config.cluster-name}") String clusterName,
            ConsulConfigFetcher consulConfigFetcher,
            ConfigurationValidator configurationValidator,
            CachedConfigHolder cachedConfigHolder,
            ApplicationEventPublisher<ClusterConfigUpdateEvent> eventPublisher,
            ConsulKvService consulKvService,
            ConsulBusinessOperationsService consulBusinessOperationsService,
            ObjectMapper objectMapper
    ) {
        this.defaultClusterName = clusterName;
        this.effectiveClusterName = clusterName; // Initialize with default, will be updated in initialize()
        this.consulConfigFetcher = consulConfigFetcher;
        this.configurationValidator = configurationValidator;
        this.cachedConfigHolder = cachedConfigHolder;
        this.eventPublisher = eventPublisher;
        this.consulKvService = consulKvService;
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
        // Use the cluster name from the parameter instead of the injected property
        this.effectiveClusterName = clusterNameFromParam;

        if (!this.defaultClusterName.equals(clusterNameFromParam)) {
            LOG.info("Initialize called with cluster name '{}', which differs from default '{}'. Using provided name.",
                    clusterNameFromParam, this.defaultClusterName);
        }

        try {
            consulConfigFetcher.connect();
            LOG.info("Attempting initial configuration load for cluster: {}", effectiveClusterName);

            try {
                Optional<PipelineClusterConfig> initialClusterConfigOpt = consulConfigFetcher.fetchPipelineClusterConfig(effectiveClusterName);
                if (initialClusterConfigOpt.isPresent()) {
                    PipelineClusterConfig initialConfig = initialClusterConfigOpt.get();
                    LOG.info("Initial configuration fetched for cluster '{}'. Processing...", effectiveClusterName);
                    processConsulUpdate(WatchCallbackResult.success(initialConfig), "Initial Load");
                } else {
                    LOG.warn("No initial configuration found for cluster '{}'. Watch will pick up first appearance or deletion.", effectiveClusterName);
                    // Ensure the cache is cleared when no initial configuration is found
                    cachedConfigHolder.clearConfiguration();
                    LOG.info("Cache cleared due to no initial configuration found for cluster '{}'.", effectiveClusterName);
                    // Current behavior: If no initial config, let the watch be the primary driver.
                    // This is acceptable as the system might still become consistent once the watch provides data.
                }
            } catch (Exception fetchEx) {
                // This catch block handles failures ONLY during the initial fetch.
                // It's reasonable to log this and still attempt to start the watch,
                // as the watch might succeed later.
                LOG.error("Error during initial configuration fetch for cluster '{}': {}. Will still attempt to start watch.",
                        effectiveClusterName, fetchEx.getMessage(), fetchEx);
                // Process this as a failure for the initial state, but don't stop initialization.
                processConsulUpdate(WatchCallbackResult.failure(fetchEx), "Initial Load Fetch Error");
            }

            // If connect() succeeded and initial fetch (even if failed) didn't stop us,
            // setting up the watch is critical.
            consulConfigFetcher.watchClusterConfig(effectiveClusterName, this::handleConsulWatchUpdate);
            LOG.info("Consul watch established for cluster configuration: {}", effectiveClusterName);

        } catch (Exception e) {
            // This outer catch block handles failures from consulConfigFetcher.connect()
            // or consulConfigFetcher.watchClusterConfig() setup.
            // These are critical failures for the manager's core functionality.
            LOG.error("CRITICAL: Failed to initialize DynamicConfigurationManager (connect or watch setup) for cluster '{}': {}",
                    this.effectiveClusterName, e.getMessage(), e);
            // Ensure the cache is cleared when a connection or watch setup failure occurs
            cachedConfigHolder.clearConfiguration();
            LOG.info("Cache cleared due to connection or watch setup failure for cluster '{}'.", effectiveClusterName);
            // Re-throw as a custom runtime exception to make the initialization failure explicit.
            // This will typically cause the bean creation to fail if called from @PostConstruct.
            throw new ConfigurationManagerInitializationException(
                    "Failed to initialize Consul connection or watch for cluster " + this.effectiveClusterName, e);
        }
    }

    private void handleConsulWatchUpdate(WatchCallbackResult watchResult) {
        processConsulUpdate(watchResult, "Consul Watch Update");
    }

    private void processConsulUpdate(WatchCallbackResult watchResult, String updateSource) {

        Optional<PipelineClusterConfig> oldConfigForEvent = cachedConfigHolder.getCurrentConfig();

        if (watchResult.hasError()) {
            LOG.error("CRITICAL: Error received from Consul source '{}' for cluster '{}': {}. Keeping previous configuration.",
                    updateSource, this.effectiveClusterName, watchResult.error().map(Throwable::getMessage).orElse("Unknown error"));
            watchResult.error().ifPresent(e -> LOG.debug("Consul source error details:", e));
            return;
        }

        if (watchResult.deleted()) { // Uses the boolean accessor from the record instance
            LOG.warn("PipelineClusterConfig for cluster '{}' indicated as deleted by source '{}'. Clearing local cache and notifying listeners.",
                    this.effectiveClusterName, updateSource);
            cachedConfigHolder.clearConfiguration();
            if (oldConfigForEvent.isPresent()) {
                PipelineClusterConfig effectivelyEmptyConfig = new PipelineClusterConfig(this.effectiveClusterName, null, null, null, null, null);
                ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfigForEvent, effectivelyEmptyConfig);
                publishEvent(event);
            } else {
                 LOG.info("Cache was already empty or no previous config to compare for deletion event from source '{}' for cluster '{}'.",
                         updateSource, this.effectiveClusterName);
            }
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

                // If any schema is missing, skip validation and treat it as a validation failure
                if (missingSchemaDetected) {
                    LOG.error("CRITICAL: New configuration for cluster '{}' from source '{}' has missing schemas.", 
                            this.effectiveClusterName, updateSource);

                    // For initial load, clear the cache and don't publish events. For watch updates, keep the old config.
                    if (updateSource.equals("Initial Load")) {
                        LOG.info("Clearing cache due to missing schemas during initial load for cluster '{}'.", this.effectiveClusterName);
                        cachedConfigHolder.clearConfiguration();
                        // Don't publish an event for initial load with missing schemas
                    } else {
                        LOG.info("Keeping previous configuration in cache due to missing schemas during watch update for cluster '{}'.", this.effectiveClusterName);
                    }
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
                    ClusterConfigUpdateEvent event = new ClusterConfigUpdateEvent(oldConfigForEvent, newConfig);
                    publishEvent(event);
                } else {
                    LOG.error("CRITICAL: New configuration for cluster '{}' from source '{}' failed validation. Errors: {}.",
                            this.effectiveClusterName, updateSource, validationResult.errors());

                    // For initial load, clear the cache and don't publish events. For watch updates, keep the old config.
                    if (updateSource.equals("Initial Load")) {
                        LOG.info("Clearing cache due to validation failure during initial load for cluster '{}'.", this.effectiveClusterName);
                        cachedConfigHolder.clearConfiguration();
                        // Don't publish an event for initial load validation failure
                    } else {
                        LOG.info("Keeping previous configuration in cache due to validation failure during watch update for cluster '{}'.", this.effectiveClusterName);
                    }
                }
            } catch (Exception e) {
                LOG.error("CRITICAL: Exception during processing of new configuration from source '{}' for cluster '{}': {}.",
                        updateSource, this.effectiveClusterName, e.getMessage(), e);

                // For initial load, clear the cache and don't publish events. For watch updates, keep the old config.
                if (updateSource.equals("Initial Load")) {
                    LOG.info("Clearing cache due to exception during initial load for cluster '{}'.", this.effectiveClusterName);
                    cachedConfigHolder.clearConfiguration();
                    // Don't publish an event for initial load with exceptions
                } else {
                    LOG.info("Keeping previous configuration in cache due to exception during watch update for cluster '{}'.", this.effectiveClusterName);
                }
            }
        } else {
            LOG.warn("Received ambiguous WatchCallbackResult (no config, no error, not deleted) from source '{}' for cluster '{}'. No action taken.",
                     updateSource, this.effectiveClusterName);
        }
    }

    private void publishEvent(ClusterConfigUpdateEvent event) {
        try {
            eventPublisher.publishEvent(event);
            listeners.forEach(listener -> {
                try {
                    listener.accept(event);
                } catch (Exception e) {
                    LOG.error("Error invoking direct config update listener for cluster {}: {}", this.effectiveClusterName, e.getMessage(), e);
                }
            });
            LOG.info("Notified listeners of configuration update for cluster '{}'. Old config present: {}, New config cluster: {}",
                this.effectiveClusterName, event.oldConfig().isPresent(), event.newConfig().clusterName());
        } catch (Exception e) {
            LOG.error("Error publishing ClusterConfigUpdateEvent for cluster {}: {}", this.effectiveClusterName, e.getMessage(), e);
        }
    }

    @Override
    public Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig() {
        return cachedConfigHolder.getCurrentConfig();
    }

    @Override
    public Optional<PipelineConfig> getPipelineConfig(String pipelineId) {
        return getCurrentPipelineClusterConfig().flatMap(clusterConfig -> Optional.ofNullable(clusterConfig.pipelineGraphConfig().getPipelineConfig(pipelineId)));
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
        LOG.info("Shutting down DynamicConfigurationManager for cluster: {}", this.effectiveClusterName);
        if (consulConfigFetcher != null) {
            try {
                consulConfigFetcher.close();
            } catch (Exception e) {
                LOG.error("Error shutting down ConsulConfigFetcher: {}", e.getMessage(), e);
            }
        }
        listeners.clear();
    }

    /**
     * Creates a deep copy of a PipelineClusterConfig by serializing and deserializing it.
     */
    private PipelineClusterConfig deepCopyConfig(PipelineClusterConfig config) {
        try {
            String json = objectMapper.writeValueAsString(config);
            return objectMapper.readValue(json, PipelineClusterConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create deep copy of config", e);
        }
    }

    /**
     * Validates and saves the updated configuration to Consul.
     * Only saves the configuration if it passes validation.
     */
    private boolean saveConfigToConsul(PipelineClusterConfig updatedConfig) {
        try {
            // Validate the configuration before saving
            Map<SchemaReference, String> schemaCacheForConfig = new HashMap<>();
            if (updatedConfig.pipelineModuleMap() != null && updatedConfig.pipelineModuleMap().availableModules() != null) {
                for (PipelineModuleConfiguration moduleConfig : updatedConfig.pipelineModuleMap().availableModules().values()) {
                    if (moduleConfig.customConfigSchemaReference() != null) {
                        SchemaReference ref = moduleConfig.customConfigSchemaReference();
                        Optional<SchemaVersionData> schemaDataOpt = consulConfigFetcher.fetchSchemaVersionData(ref.subject(), ref.version());
                        if (schemaDataOpt.isPresent() && schemaDataOpt.get().schemaContent() != null) {
                            schemaCacheForConfig.put(ref, schemaDataOpt.get().schemaContent());
                        } else {
                            LOG.warn("Schema content not found for reference {} during validation before saving. Validation may fail for steps using this module.",
                                    ref);
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

            // Configuration passed validation, proceed with saving
            String clusterName = updatedConfig.clusterName();

            // Use ConsulBusinessOperationsService instead of direct ConsulKvService
            boolean success = consulBusinessOperationsService.storeClusterConfiguration(clusterName, updatedConfig).block();
            if (success) {
                LOG.info("Successfully saved updated configuration to Consul for cluster: {}", updatedConfig.clusterName());
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
        if (!currentConfigOpt.isPresent()) {
            LOG.error("Cannot add Kafka topic: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        PipelineClusterConfig currentConfig = currentConfigOpt.get();
        Set<String> updatedTopics = new HashSet<>(currentConfig.allowedKafkaTopics());

        // Check if topic already exists
        if (updatedTopics.contains(newTopic)) {
            LOG.info("Kafka topic '{}' already exists in allowed topics for cluster: {}", newTopic, this.effectiveClusterName);
            return true;
        }

        updatedTopics.add(newTopic);

        PipelineClusterConfig updatedConfig = PipelineClusterConfig.builder()
                .clusterName(currentConfig.clusterName())
                .pipelineGraphConfig(currentConfig.pipelineGraphConfig())
                .pipelineModuleMap(currentConfig.pipelineModuleMap())
                .defaultPipelineName(currentConfig.defaultPipelineName())
                .allowedKafkaTopics(updatedTopics)
                .allowedGrpcServices(currentConfig.allowedGrpcServices())
                .build();

        return saveConfigToConsul(updatedConfig);
    }

    @Override
    public boolean updatePipelineStepToUseKafkaTopic(String pipelineName, String stepName, 
                                                    String outputKey, String newTopic, String targetStepName) {
        LOG.info("Updating pipeline step '{}' in pipeline '{}' to use Kafka topic '{}' for output '{}' targeting '{}'", 
                stepName, pipelineName, newTopic, outputKey, targetStepName);

        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (!currentConfigOpt.isPresent()) {
            LOG.error("Cannot update pipeline step: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        // Create a deep copy of the config
        PipelineClusterConfig updatedConfig = deepCopyConfig(currentConfigOpt.get());

        // Get the pipeline and step
        PipelineConfig pipeline = updatedConfig.pipelineGraphConfig().pipelines().get(pipelineName);
        if (pipeline == null) {
            LOG.error("Pipeline '{}' not found in cluster: {}", pipelineName, this.effectiveClusterName);
            return false;
        }

        PipelineStepConfig step = pipeline.pipelineSteps().get(stepName);
        if (step == null) {
            LOG.error("Step '{}' not found in pipeline '{}' in cluster: {}", stepName, pipelineName, this.effectiveClusterName);
            return false;
        }

        // Create a new output for the step
        KafkaTransportConfig kafkaTransport = new KafkaTransportConfig(
                newTopic,
                Map.of("compression.type", "snappy")
        );

        PipelineStepConfig.OutputTarget newOutput = new PipelineStepConfig.OutputTarget(
                targetStepName,
                TransportType.KAFKA,
                null,
                kafkaTransport
        );

        // Add the new output to the step
        Map<String, PipelineStepConfig.OutputTarget> updatedOutputs = new HashMap<>(step.outputs());
        updatedOutputs.put(outputKey, newOutput);

        // Create an updated step with the new output
        PipelineStepConfig updatedStep = PipelineStepConfig.builder()
                .stepName(step.stepName())
                .stepType(step.stepType())
                .description(step.description())
                .customConfigSchemaId(step.customConfigSchemaId())
                .customConfig(step.customConfig())
                .kafkaInputs(step.kafkaInputs())
                .processorInfo(step.processorInfo())
                .outputs(updatedOutputs)
                .build();

        // Update the step in the pipeline
        Map<String, PipelineStepConfig> updatedSteps = new HashMap<>(pipeline.pipelineSteps());
        updatedSteps.put(updatedStep.stepName(), updatedStep);

        // Create an updated pipeline with the updated step
        PipelineConfig updatedPipeline = new PipelineConfig(
                pipeline.name(),
                updatedSteps
        );

        // Update the pipeline in the graph config
        Map<String, PipelineConfig> updatedPipelines = new HashMap<>(updatedConfig.pipelineGraphConfig().pipelines());
        updatedPipelines.put(updatedPipeline.name(), updatedPipeline);

        // Create an updated graph config with the updated pipeline
        PipelineGraphConfig updatedGraphConfig = new PipelineGraphConfig(updatedPipelines);

        // Create an updated cluster config with the updated graph config
        PipelineClusterConfig finalConfig = PipelineClusterConfig.builder()
                .clusterName(updatedConfig.clusterName())
                .pipelineGraphConfig(updatedGraphConfig)
                .pipelineModuleMap(updatedConfig.pipelineModuleMap())
                .defaultPipelineName(updatedConfig.defaultPipelineName())
                .allowedKafkaTopics(updatedConfig.allowedKafkaTopics())
                .allowedGrpcServices(updatedConfig.allowedGrpcServices())
                .build();

        return saveConfigToConsul(finalConfig);
    }

    @Override
    public boolean deleteServiceAndUpdateConnections(String serviceName) {
        LOG.info("Deleting service '{}' and updating all connections to/from it for cluster: {}", serviceName, this.effectiveClusterName);

        Optional<PipelineClusterConfig> currentConfigOpt = getCurrentPipelineClusterConfig();
        if (!currentConfigOpt.isPresent()) {
            LOG.error("Cannot delete service: No current configuration available for cluster: {}", this.effectiveClusterName);
            return false;
        }

        // Create a deep copy of the config
        PipelineClusterConfig updatedConfig = deepCopyConfig(currentConfigOpt.get());

        // Remove the service from allowed gRPC services
        Set<String> updatedServices = new HashSet<>(updatedConfig.allowedGrpcServices());
        if (!updatedServices.contains(serviceName)) {
            LOG.warn("Service '{}' not found in allowed gRPC services for cluster: {}", serviceName, this.effectiveClusterName);
        }
        updatedServices.remove(serviceName);

        // Remove the service from the module map
        Map<String, PipelineModuleConfiguration> updatedModules = 
                new HashMap<>(updatedConfig.pipelineModuleMap().availableModules());
        updatedModules.remove(serviceName);
        PipelineModuleMap updatedModuleMap = new PipelineModuleMap(updatedModules);

        // Update all pipelines to remove connections to/from the service
        Map<String, PipelineConfig> updatedPipelines = new HashMap<>();

        for (Map.Entry<String, PipelineConfig> pipelineEntry : updatedConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();

            // Check if any steps in this pipeline need to be updated or removed
            Map<String, PipelineStepConfig> updatedSteps = new HashMap<>();
            boolean pipelineModified = false;

            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                String stepName = stepEntry.getKey();
                PipelineStepConfig step = stepEntry.getValue();

                // Check if this step uses the service to be deleted
                boolean stepUsesService = false;
                if (step.processorInfo() != null && 
                    serviceName.equals(step.processorInfo().grpcServiceName())) {
                    stepUsesService = true;
                    pipelineModified = true;
                    LOG.info("Step '{}' in pipeline '{}' uses service '{}' and will be removed", 
                            stepName, pipelineName, serviceName);
                    continue; // Skip this step (remove it)
                }

                // Check if this step has outputs to the service
                Map<String, PipelineStepConfig.OutputTarget> updatedOutputs = new HashMap<>();
                boolean outputsModified = false;

                for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : step.outputs().entrySet()) {
                    String outputKey = outputEntry.getKey();
                    PipelineStepConfig.OutputTarget output = outputEntry.getValue();

                    boolean outputUsesService = false;

                    // Check if this output uses the service via gRPC
                    if (output.transportType() == TransportType.GRPC && 
                        output.grpcTransport() != null && 
                        serviceName.equals(output.grpcTransport().serviceName())) {
                        outputUsesService = true;
                    }

                    // Check if this output targets a step that uses the service
                    if (output.targetStepName() != null) {
                        String targetStepName = output.targetStepName();
                        // Handle cross-pipeline references (contains a dot)
                        if (targetStepName.contains(".")) {
                            String[] parts = targetStepName.split("\\.", 2);
                            String targetPipelineName = parts[0];
                            String targetStepNameInPipeline = parts[1];

                            PipelineConfig targetPipeline = updatedConfig.pipelineGraphConfig().pipelines().get(targetPipelineName);
                            if (targetPipeline != null) {
                                PipelineStepConfig targetStep = targetPipeline.pipelineSteps().get(targetStepNameInPipeline);
                                if (targetStep != null && 
                                    targetStep.processorInfo() != null && 
                                    serviceName.equals(targetStep.processorInfo().grpcServiceName())) {
                                    outputUsesService = true;
                                }
                            }
                        } else {
                            // Same pipeline
                            PipelineStepConfig targetStep = pipeline.pipelineSteps().get(targetStepName);
                            if (targetStep != null && 
                                targetStep.processorInfo() != null && 
                                serviceName.equals(targetStep.processorInfo().grpcServiceName())) {
                                outputUsesService = true;
                            }
                        }
                    }

                    if (!outputUsesService) {
                        updatedOutputs.put(outputKey, output);
                    } else {
                        outputsModified = true;
                        pipelineModified = true;
                        LOG.info("Output '{}' from step '{}' in pipeline '{}' uses service '{}' and will be removed", 
                                outputKey, stepName, pipelineName, serviceName);
                    }
                }

                if (outputsModified) {
                    // Create an updated step with the modified outputs
                    PipelineStepConfig updatedStep = PipelineStepConfig.builder()
                            .stepName(step.stepName())
                            .stepType(step.stepType())
                            .description(step.description())
                            .customConfigSchemaId(step.customConfigSchemaId())
                            .customConfig(step.customConfig())
                            .kafkaInputs(step.kafkaInputs())
                            .processorInfo(step.processorInfo())
                            .outputs(updatedOutputs)
                            .build();
                    updatedSteps.put(stepName, updatedStep);
                } else if (!stepUsesService) {
                    // Keep the step unchanged
                    updatedSteps.put(stepName, step);
                }
            }

            if (pipelineModified) {
                // Create an updated pipeline with the modified steps
                PipelineConfig updatedPipeline = new PipelineConfig(
                        pipeline.name(),
                        updatedSteps
                );
                updatedPipelines.put(pipelineName, updatedPipeline);
                LOG.info("Pipeline '{}' modified to remove connections to/from service '{}'", 
                        pipelineName, serviceName);
            } else {
                // Keep the pipeline unchanged
                updatedPipelines.put(pipelineName, pipeline);
            }
        }

        // Create an updated graph config with the updated pipelines
        PipelineGraphConfig updatedGraphConfig = new PipelineGraphConfig(updatedPipelines);

        // Create an updated cluster config with the updated graph config, module map, and services
        PipelineClusterConfig finalConfig = PipelineClusterConfig.builder()
                .clusterName(updatedConfig.clusterName())
                .pipelineGraphConfig(updatedGraphConfig)
                .pipelineModuleMap(updatedModuleMap)
                .defaultPipelineName(updatedConfig.defaultPipelineName())
                .allowedKafkaTopics(updatedConfig.allowedKafkaTopics())
                .allowedGrpcServices(updatedServices)
                .build();

        return saveConfigToConsul(finalConfig);
    }
}
