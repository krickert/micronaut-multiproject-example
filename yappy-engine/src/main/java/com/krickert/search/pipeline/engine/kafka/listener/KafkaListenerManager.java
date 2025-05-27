// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/KafkaListenerManager.java
package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent; // NEW EVENT
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.admin.KafkaAdminService;
import com.krickert.search.pipeline.engine.kafka.admin.OffsetResetParameters;
import com.krickert.search.pipeline.engine.kafka.admin.OffsetResetStrategy;
import io.apicurio.registry.serde.config.SerdeConfig;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener; // For Micronaut events
import io.micronaut.core.annotation.NonNull;
import io.micronaut.scheduling.annotation.Async; // For async event listener
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class KafkaListenerManager implements ApplicationEventListener<PipelineClusterConfigChangeEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaListenerManager.class);

    private final DefaultKafkaListenerPool listenerPool;
    private final ConsumerStateManager stateManager;
    private final KafkaAdminService kafkaAdminService;
    private final PipeStreamEngine pipeStreamEngine;
    private final ApplicationContext applicationContext;
    @Getter
    private final String configuredSchemaRegistryType;
    private final String appClusterName;

    /**
     * Map to track active listeners.
     * Key: listenerInstanceKey (e.g., "pipelineName:stepName:topic:groupId")
     * Value: The DynamicKafkaListener instance itself.
     */
    private final Map<String, DynamicKafkaListener> activeListenerInstanceMap = new ConcurrentHashMap<>();

    @Inject
    public KafkaListenerManager(
            DefaultKafkaListenerPool listenerPool,
            ConsumerStateManager stateManager,
            KafkaAdminService kafkaAdminService,
            PipeStreamEngine pipeStreamEngine,
            ApplicationContext applicationContext,
            @Value("${kafka.schema.registry.type:none}") String configuredSchemaRegistryType,
            @Value("${app.config.cluster-name}") String appClusterName) {
        this.listenerPool = listenerPool;
        this.stateManager = stateManager;
        this.kafkaAdminService = kafkaAdminService;
        this.pipeStreamEngine = pipeStreamEngine;
        this.applicationContext = applicationContext;
        this.configuredSchemaRegistryType = configuredSchemaRegistryType.toLowerCase(Locale.ROOT);
        this.appClusterName = appClusterName;
        LOG.info("KafkaListenerManager initialized for cluster '{}' with schema registry type: '{}'",
                this.appClusterName, this.configuredSchemaRegistryType);
    }

    @Override
    @Async // Process events asynchronously to avoid blocking the event publisher
    public void onApplicationEvent(@NonNull PipelineClusterConfigChangeEvent event) {
        LOG.info("Received PipelineClusterConfigChangeEvent for cluster: {}. Current app cluster: {}. Is deletion: {}",
                event.clusterName(), this.appClusterName, event.isDeletion());

        if (!this.appClusterName.equals(event.clusterName())) {
            LOG.debug("Ignoring config update event for cluster '{}' as this manager is for cluster '{}'.",
                    event.clusterName(), this.appClusterName);
            return;
        }
        synchronizeListeners(event.newConfig(), event.isDeletion());
    }

    private synchronized void synchronizeListeners(PipelineClusterConfig newClusterConfig, boolean isDeletion) {
        LOG.info("Synchronizing Kafka listeners for cluster '{}'. Is deletion: {}", this.appClusterName, isDeletion);

        if (isDeletion) {
            LOG.warn("Cluster configuration for '{}' was deleted. Shutting down all active listeners for this cluster.", this.appClusterName);
            new HashSet<>(activeListenerInstanceMap.keySet()).forEach(this::removeListenerInstance);
            activeListenerInstanceMap.clear();
            LOG.info("All Kafka listeners for cluster '{}' have been shut down and removed due to config deletion.", this.appClusterName);
            return;
        }

        if (newClusterConfig == null) {
            LOG.error("Received non-deletion event for cluster '{}' but newClusterConfig is null. This is unexpected. No listeners will be changed.", this.appClusterName);
            return;
        }

        if (!this.appClusterName.equals(newClusterConfig.clusterName())) {
            LOG.warn("SynchronizeListeners called with config for cluster '{}', but this manager is for '{}'. Ignoring.",
                    newClusterConfig.clusterName(), this.appClusterName);
            return;
        }

        Set<String> desiredListenerInstanceKeys = new HashSet<>();
        if (newClusterConfig.pipelineGraphConfig() != null && newClusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (PipelineConfig pipeline : newClusterConfig.pipelineGraphConfig().pipelines().values()) {
                if (pipeline.pipelineSteps() == null) continue;
                for (PipelineStepConfig step : pipeline.pipelineSteps().values()) {
                    if (step.kafkaInputs() == null) continue;
                    for (KafkaInputDefinition inputDef : step.kafkaInputs()) {
                        if (inputDef.listenTopics() == null || inputDef.listenTopics().isEmpty()) continue;
                        for (String topic : inputDef.listenTopics()) {
                            String groupId = determineConsumerGroupId(pipeline.name(), step.stepName(), inputDef);
                            String listenerKey = generateListenerInstanceKey(pipeline.name(), step.stepName(), topic, groupId);
                            desiredListenerInstanceKeys.add(listenerKey);

                            DynamicKafkaListener existingListener = activeListenerInstanceMap.get(listenerKey);
                            if (existingListener == null) {
                                LOG.info("New listener instance required for key: {}", listenerKey);
                                createAndRegisterListenerInstance(pipeline.name(), step.stepName(), topic, groupId, inputDef.kafkaConsumerProperties());
                            } else {
                                // Check if core identity or properties changed.
                                // For simplicity, we'll recreate if properties map is different.
                                // A more granular check on specific properties might be needed if recreation is too disruptive.
                                if (!existingListener.getTopic().equals(topic) ||
                                        !existingListener.getGroupId().equals(groupId) ||
                                        !areConsumerPropertiesEqual(existingListener.getConsumerConfigForComparison(), inputDef.kafkaConsumerProperties())) {
                                    LOG.info("Configuration changed for listener instance key: {}. Recreating listener.", listenerKey);
                                    removeListenerInstance(listenerKey);
                                    createAndRegisterListenerInstance(pipeline.name(), step.stepName(), topic, groupId, inputDef.kafkaConsumerProperties());
                                } else {
                                    LOG.debug("Listener instance for key {} already exists and configuration appears unchanged.", listenerKey);
                                }
                            }
                        }
                    }
                }
            }
        }

        Set<String> listenersToRemove = new HashSet<>(activeListenerInstanceMap.keySet());
        listenersToRemove.removeAll(desiredListenerInstanceKeys);
        if (!listenersToRemove.isEmpty()) {
            LOG.info("Removing {} listener instance(s) that are no longer in the desired configuration: {}", listenersToRemove.size(), listenersToRemove);
            listenersToRemove.forEach(this::removeListenerInstance);
        }

        LOG.info("Kafka listeners synchronization complete for cluster '{}'. Active listeners: {}", this.appClusterName, activeListenerInstanceMap.size());
    }

    private String determineConsumerGroupId(String pipelineName, String stepName, KafkaInputDefinition inputDef) {
        if (inputDef.consumerGroupId() != null && !inputDef.consumerGroupId().isBlank()) {
            return inputDef.consumerGroupId();
        }
        String defaultGroupId = String.format("yappy-%s-%s-%s-group", this.appClusterName, pipelineName, stepName);
        LOG.debug("Generated default consumer group ID '{}' for pipeline '{}', step '{}'", defaultGroupId, pipelineName, stepName);
        return defaultGroupId;
    }

    private boolean areConsumerPropertiesEqual(Map<String, Object> currentListenerConfigProps, Map<String, String> newStepDefProps) {
        // This comparison needs to be robust.
        // The currentListenerConfigProps are <String, Object> and newStepDefProps are <String, String>.
        // We should compare the relevant properties that would necessitate a listener recreation.
        // For now, a simple size and key-value check (converting newStepDefProps values to objects or vice-versa)
        if (currentListenerConfigProps == null && (newStepDefProps == null || newStepDefProps.isEmpty())) return true;
        if (currentListenerConfigProps == null || newStepDefProps == null) return false;

        // Filter out properties that are dynamically added by the manager (like bootstrap.servers, deserializers, schema registry urls)
        // and only compare the ones provided in KafkaInputDefinition.kafkaConsumerProperties
        Map<String, String> relevantCurrentProps = new HashMap<>();
        currentListenerConfigProps.forEach((key, value) -> {
            // Only consider properties that would have come from the original definition
            if (!(key.equals(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG) ||
                    key.equals(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG) ||
                    key.equals(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG) ||
                    key.equals(ConsumerConfig.GROUP_ID_CONFIG) ||
                    key.startsWith("apicurio.registry") || // Example for Apicurio
                    key.startsWith("aws.glue"))) { // Example for Glue
                if (value != null) {
                    relevantCurrentProps.put(key, value.toString());
                }
            }
        });
        return relevantCurrentProps.equals(newStepDefProps);
    }

    private void createAndRegisterListenerInstance(
            String pipelineName,
            String stepName,
            String topic,
            String groupId,
            Map<String, String> consumerConfigFromStep) { // consumerConfigFromStep holds the original properties

        String listenerInstanceKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        String uniquePoolListenerId = "kafka-listener-" + UUID.randomUUID().toString();

        LOG.info("Creating new listener instance. Key: '{}', Pool ID: '{}', Topic: '{}', Group: '{}'",
                listenerInstanceKey, uniquePoolListenerId, topic, groupId);

        try {
            Map<String, Object> finalConsumerConfig = new HashMap<>(consumerConfigFromStep != null ? consumerConfigFromStep : Collections.emptyMap());

            addBootstrapServers(finalConsumerConfig, uniquePoolListenerId);

            LOG.debug("Listener (Pool ID: {}): Configuring for schema registry type: '{}'", uniquePoolListenerId, configuredSchemaRegistryType);
            switch (configuredSchemaRegistryType) {
                case "apicurio":
                    addApicurioConsumerProperties(finalConsumerConfig, uniquePoolListenerId);
                    break;
                case "glue":
                    addGlueConsumerProperties(finalConsumerConfig, uniquePoolListenerId);
                    break;
                case "none":
                default:
                    LOG.warn("Listener (Pool ID: {}): Schema registry type is '{}' or unknown. " +
                            "No specific schema registry properties will be added. " +
                            "Ensure deserializer is correctly configured if needed.", uniquePoolListenerId, configuredSchemaRegistryType);
                    finalConsumerConfig.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            "io.micronaut.protobuf.serialize.ProtobufDeserializer");
                    break;
            }

            LOG.debug("Listener (Pool ID: {}): Final consumer config before passing to pool: {}", uniquePoolListenerId, finalConsumerConfig);

            // ***** CORRECTED CALL *****
            DynamicKafkaListener listener = listenerPool.createListener(
                    uniquePoolListenerId,
                    topic,
                    groupId,
                    finalConsumerConfig, // This is the merged config
                    consumerConfigFromStep, // This is the original properties from the step definition
                    pipelineName,
                    stepName,
                    pipeStreamEngine);
            // ***** END OF CORRECTION *****

            activeListenerInstanceMap.put(listenerInstanceKey, listener);
            stateManager.updateState(uniquePoolListenerId, new ConsumerState(
                    uniquePoolListenerId, topic, groupId, false, Instant.now(), Collections.emptyMap()));
            LOG.info("Successfully created and registered Kafka listener. Key: '{}', Pool ID: '{}'", listenerInstanceKey, uniquePoolListenerId);

        } catch (Exception e) {
            LOG.error("Failed to create Kafka listener instance. Key: '{}', Topic: '{}', Group: '{}'. Error: {}",
                    listenerInstanceKey, topic, groupId, e.getMessage(), e);
        }
    }

    private void removeListenerInstance(String listenerInstanceKey) {
        DynamicKafkaListener listener = activeListenerInstanceMap.remove(listenerInstanceKey);
        if (listener != null) {
            String uniquePoolListenerId = listener.getListenerId();
            LOG.info("Removing listener instance. Key: '{}', Pool ID: '{}'", listenerInstanceKey, uniquePoolListenerId);
            try {
                listener.shutdown();
                listenerPool.removeListener(uniquePoolListenerId);
                stateManager.removeState(uniquePoolListenerId);
                LOG.info("Successfully removed and shut down listener. Key: '{}', Pool ID: '{}'", listenerInstanceKey, uniquePoolListenerId);
            } catch (Exception e) {
                LOG.error("Error during shutdown/removal of listener. Key: '{}', Pool ID: '{}'. Error: {}",
                        listenerInstanceKey, uniquePoolListenerId, e.getMessage(), e);
            }
        } else {
            LOG.warn("Attempted to remove listener instance with key '{}', but it was not found in active map.", listenerInstanceKey);
        }
    }

    private void addBootstrapServers(Map<String, Object> consumerConfig, String uniquePoolListenerId) {
        String bootstrapServersPropKeyDefault = "kafka.consumers.default.bootstrap.servers";
        String bootstrapServersPropKeyGlobal = "kafka.bootstrap.servers";

        LOG.debug("Listener (Pool ID: {}): Attempting to resolve bootstrap servers. Checking property: '{}'", uniquePoolListenerId, bootstrapServersPropKeyDefault);
        Optional<String> bootstrapServersFromDefaultConsumerPath = applicationContext.getProperty(bootstrapServersPropKeyDefault, String.class);

        String resolvedBootstrapServers = bootstrapServersFromDefaultConsumerPath.orElseGet(() -> {
            LOG.warn("Listener (Pool ID: {}): Could not find '{}', trying global '{}'", uniquePoolListenerId, bootstrapServersPropKeyDefault, bootstrapServersPropKeyGlobal);
            return applicationContext.getProperty(bootstrapServersPropKeyGlobal, String.class).orElse(null);
        });

        if (resolvedBootstrapServers != null && !resolvedBootstrapServers.isBlank()) {
            consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolvedBootstrapServers);
            LOG.info("Listener (Pool ID: {}): Added '{}' = '{}' to consumerConfig", uniquePoolListenerId, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolvedBootstrapServers);
        } else {
            LOG.error("Listener (Pool ID: {}): CRITICAL - Bootstrap servers are null or blank. NOT ADDING TO CONFIG. KafkaConsumer will fail.", uniquePoolListenerId);
        }
    }

    private void addApicurioConsumerProperties(Map<String, Object> consumerConfig, String uniquePoolListenerId) {
        LOG.info("Listener (Pool ID: {}): Adding Apicurio consumer properties.", uniquePoolListenerId);
        consumerConfig.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");

        String urlPropKey = "kafka.consumers.default." + SerdeConfig.REGISTRY_URL;
        applicationContext.getProperty(urlPropKey, String.class).ifPresentOrElse(
                url -> {
                    consumerConfig.put(SerdeConfig.REGISTRY_URL, url);
                    LOG.info("Listener (Pool ID: {}): Added Apicurio property '{}' = '{}'", uniquePoolListenerId, SerdeConfig.REGISTRY_URL, url);
                },
                () -> LOG.error("Listener (Pool ID: {}): Apicurio property '{}' not found in application context.", uniquePoolListenerId, urlPropKey)
        );

        String returnClassPropKey = "kafka.consumers.default." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS;
        String returnClass = applicationContext.getProperty(returnClassPropKey, String.class).orElse(PipeStream.class.getName());
        consumerConfig.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);
        LOG.info("Listener (Pool ID: {}): Added Apicurio property '{}' = '{}'", uniquePoolListenerId, SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);

        String strategyPropKey = "kafka.consumers.default." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
        String strategy = applicationContext.getProperty(strategyPropKey, String.class).orElse(io.apicurio.registry.serde.strategy.TopicIdStrategy.class.getName());
        consumerConfig.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, strategy);
        LOG.info("Listener (Pool ID: {}): Added Apicurio property '{}' = '{}'", uniquePoolListenerId, SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, strategy);

        String explicitGroupIdPropKey = "kafka.consumers.default." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
        applicationContext.getProperty(explicitGroupIdPropKey, String.class).ifPresent( value -> {
            consumerConfig.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, value);
            LOG.info("Listener (Pool ID: {}): Added Apicurio property '{}' = '{}'", uniquePoolListenerId, SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, value);
        });
    }

    private void addGlueConsumerProperties(Map<String, Object> consumerConfig, String uniquePoolListenerId) {
        LOG.warn("Listener (Pool ID: {}): Placeholder for addGlueConsumerProperties. AWS Glue Schema Registry consumer configuration not yet implemented.", uniquePoolListenerId);
        // TODO: Implement Glue properties fetching from ApplicationContext and adding to consumerConfig
        // Example:
        // consumerConfig.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "software.amazon.awssdk.services.glue.schemaRegistry.serde.GlueSchemaRegistryKafkaDeserializer");
        // String region = applicationContext.getProperty("aws.region", String.class).orElse("us-east-1");
        // consumerConfig.put(software.amazon.awssdk.services.glue.schema.registry.configs.AWSSchemaRegistryConstants.AWS_REGION, region);
        // ... other Glue specific properties
    }

    public CompletableFuture<Void> pauseConsumer(String pipelineName, String stepName, String topic, String groupId) {
        String listenerKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        DynamicKafkaListener listener = activeListenerInstanceMap.get(listenerKey);

        if (listener == null) {
            String errorMsg = String.format("No listener found for key: %s (pipeline: %s, step: %s, topic: %s, group: %s)",
                    listenerKey, pipelineName, stepName, topic, groupId);
            LOG.warn(errorMsg);
            return CompletableFuture.failedFuture(new IllegalArgumentException(errorMsg));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            listener.pause();
            stateManager.updateState(listener.getListenerId(), new ConsumerState(
                    listener.getListenerId(), listener.getTopic(), listener.getGroupId(),
                    true, Instant.now(), Collections.emptyMap()));
            result.complete(null);
            LOG.info("Paused Kafka consumer. Key: '{}', Pool ID: '{}'", listenerKey, listener.getListenerId());
        } catch (Exception e) {
            LOG.error("Failed to pause Kafka consumer. Key: '{}', Pool ID: '{}'. Error: {}",
                    listenerKey, listener.getListenerId(), e.getMessage(), e);
            result.completeExceptionally(e);
        }
        return result;
    }

    public CompletableFuture<Void> resumeConsumer(String pipelineName, String stepName, String topic, String groupId) {
        String listenerKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        DynamicKafkaListener listener = activeListenerInstanceMap.get(listenerKey);

        if (listener == null) {
            String errorMsg = String.format("No listener found for key: %s to resume.", listenerKey);
            LOG.warn(errorMsg);
            return CompletableFuture.failedFuture(new IllegalArgumentException(errorMsg));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            listener.resume();
            stateManager.updateState(listener.getListenerId(), new ConsumerState(
                    listener.getListenerId(), listener.getTopic(), listener.getGroupId(),
                    false, Instant.now(), Collections.emptyMap()));
            result.complete(null);
            LOG.info("Resumed Kafka consumer. Key: '{}', Pool ID: '{}'", listenerKey, listener.getListenerId());
        } catch (Exception e) {
            LOG.error("Failed to resume Kafka consumer. Key: '{}', Pool ID: '{}'. Error: {}",
                    listenerKey, listener.getListenerId(), e.getMessage(), e);
            result.completeExceptionally(e);
        }
        return result;
    }

    public CompletableFuture<Void> resetOffsetToDate(String pipelineName, String stepName, String topic, String groupId, Instant date) {
        String listenerKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        DynamicKafkaListener listener = activeListenerInstanceMap.get(listenerKey);
        if (listener == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No listener for key: " + listenerKey));
        }
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.TO_TIMESTAMP)
                .timestamp(date.toEpochMilli())
                .build();
        LOG.info("Resetting Kafka consumer offset to date {} for key: '{}', Pool ID: '{}'",
                date, listenerKey, listener.getListenerId());

        return pauseConsumer(pipelineName, stepName, topic, groupId)
                .thenCompose(v -> kafkaAdminService.resetConsumerGroupOffsetsAsync(listener.getGroupId(), listener.getTopic(), params))
                .thenCompose(v -> resumeConsumer(pipelineName, stepName, topic, groupId));
    }

    public CompletableFuture<Void> resetOffsetToEarliest(String pipelineName, String stepName, String topic, String groupId) {
        String listenerKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        DynamicKafkaListener listener = activeListenerInstanceMap.get(listenerKey);
        if (listener == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No listener for key: " + listenerKey));
        }
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.EARLIEST).build();
        LOG.info("Resetting offset to earliest for key: '{}', Pool ID: '{}'", listenerKey, listener.getListenerId());
        return pauseConsumer(pipelineName, stepName, topic, groupId)
                .thenCompose(v -> kafkaAdminService.resetConsumerGroupOffsetsAsync(listener.getGroupId(), listener.getTopic(), params))
                .thenCompose(v -> resumeConsumer(pipelineName, stepName, topic, groupId));
    }

    public CompletableFuture<Void> resetOffsetToLatest(String pipelineName, String stepName, String topic, String groupId) {
        String listenerKey = generateListenerInstanceKey(pipelineName, stepName, topic, groupId);
        DynamicKafkaListener listener = activeListenerInstanceMap.get(listenerKey);
        if (listener == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No listener for key: " + listenerKey));
        }
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.LATEST).build();
        LOG.info("Resetting offset to latest for key: '{}', Pool ID: '{}'", listenerKey, listener.getListenerId());
        return pauseConsumer(pipelineName, stepName, topic, groupId)
                .thenCompose(v -> kafkaAdminService.resetConsumerGroupOffsetsAsync(listener.getGroupId(), listener.getTopic(), params))
                .thenCompose(v -> resumeConsumer(pipelineName, stepName, topic, groupId));
    }

    public Map<String, ConsumerStatus> getConsumerStatuses() {
        return activeListenerInstanceMap.values().stream()
                .map(listener -> {
                    ConsumerState state = stateManager.getState(listener.getListenerId());
                    return new ConsumerStatus(
                            listener.getListenerId(),
                            listener.getPipelineName(),
                            listener.getStepName(),
                            listener.getTopic(),
                            listener.getGroupId(),
                            listener.isPaused(),
                            state != null ? state.lastUpdated() : Instant.now()
                    );
                })
                .collect(Collectors.toMap(
                        // Use the listenerInstanceKey for the status map key if that's how users will query
                        status -> generateListenerInstanceKey(status.pipelineName(), status.stepName(), status.topic(), status.groupId()),
                        status -> status,
                        (existing, replacement) -> existing // In case of duplicate keys, which shouldn't happen with unique pool IDs
                ));
    }

    private String generateListenerInstanceKey(String pipelineName, String stepName, String topic, String groupId) {
        return String.format("%s:%s:%s:%s", pipelineName, stepName, topic, groupId);
    }
}