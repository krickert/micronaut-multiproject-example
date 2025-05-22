// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/KafkaListenerManager.java
package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
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
import io.micronaut.context.annotation.Value; // Import @Value
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages Kafka listeners for pipeline steps.
 *
 * This class is responsible for:
 * 1. Creating and managing Kafka listeners based on pipeline configuration
 * 2. Pausing and resuming Kafka consumers
 * 3. Resetting consumer offsets (including by date)
 * 4. Providing status information about Kafka consumers
 *
 * The KafkaListenerManager works with the DynamicConfigurationManager to create
 * listeners based on pipeline configuration, and with the KafkaAdminService to
 * manage consumer offsets.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class KafkaListenerManager {
    private static final Logger log = LoggerFactory.getLogger(KafkaListenerManager.class);

    private final KafkaListenerPool listenerPool;
    private final ConsumerStateManager stateManager;
    private final KafkaAdminService kafkaAdminService;
    private final DynamicConfigurationManager configManager;
    private final PipeStreamEngine pipeStreamEngine;
    private final ApplicationContext applicationContext;
    private final String configuredSchemaRegistryType;

    /**
     * Map to track which listeners have been created for which pipeline steps.
     * Key: pipelineName:stepName, Value: listenerId
     */
    private final Map<String, String> pipelineStepToListenerMap = new ConcurrentHashMap<>();

    @Inject
    public KafkaListenerManager(
            KafkaListenerPool listenerPool,
            ConsumerStateManager stateManager,
            KafkaAdminService kafkaAdminService,
            DynamicConfigurationManager configManager,
            PipeStreamEngine pipeStreamEngine,
            ApplicationContext applicationContext,
            @Value("${kafka.schema.registry.type:none}") String configuredSchemaRegistryType) {
        this.listenerPool = listenerPool;
        this.stateManager = stateManager;
        this.kafkaAdminService = kafkaAdminService;
        this.configManager = configManager;
        this.pipeStreamEngine = pipeStreamEngine;
        this.applicationContext = applicationContext;
        this.configuredSchemaRegistryType = configuredSchemaRegistryType.toLowerCase(Locale.ROOT);
        log.info("KafkaListenerManager initialized with schema registry type: '{}'", this.configuredSchemaRegistryType);
    }

    /**
     * Creates Kafka listeners for a pipeline based on configuration.
     *
     * @param pipelineName The name of the pipeline
     * @return A list of created listener IDs
     */
    public List<String> createListenersForPipeline(String pipelineName) {
        // ... (no changes from your existing correct version)
        log.info("Creating Kafka listeners for pipeline: {}", pipelineName);
        List<String> createdListeners = new ArrayList<>();

        Optional<PipelineConfig> pipelineConfigOpt = configManager.getPipelineConfig(pipelineName);
        if (pipelineConfigOpt.isEmpty()) {
            log.warn("Cannot create listeners for non-existent pipeline: {}", pipelineName);
            return createdListeners;
        }
        PipelineConfig pipelineConfig = pipelineConfigOpt.get();

        if (pipelineConfig.pipelineSteps() == null || pipelineConfig.pipelineSteps().isEmpty()) {
            log.info("Pipeline '{}' has no steps defined. No Kafka listeners will be created.", pipelineName);
            return createdListeners;
        }

        pipelineConfig.pipelineSteps().forEach((stepName, stepConfig) -> {
            if (stepConfig.kafkaInputs() != null && !stepConfig.kafkaInputs().isEmpty()) {
                for (KafkaInputDefinition kafkaInput : stepConfig.kafkaInputs()) {
                    if (kafkaInput.listenTopics() != null) {
                        for (String topic : kafkaInput.listenTopics()) {
                            String groupId = kafkaInput.consumerGroupId();
                            if (groupId == null || groupId.isBlank()) {
                                groupId = "yappy-" + pipelineName + "-" + stepName + "-group";
                                log.info("Generated consumer group ID for pipeline {}, step {}: {}", pipelineName, stepName, groupId);
                            }
                            DynamicKafkaListener listener = createListener(
                                    pipelineName,
                                    stepName,
                                    topic,
                                    groupId,
                                    kafkaInput.kafkaConsumerProperties()
                            );
                            if (listener != null) {
                                createdListeners.add(listener.getListenerId());
                            }
                        }
                    }
                }
            }
        });

        log.info("Created {} Kafka listeners for pipeline: {}", createdListeners.size(), pipelineName);
        return createdListeners;
    }

    /**
     * Creates a single Kafka listener.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param topic The Kafka topic to listen to
     * @param groupId The consumer group ID
     * @param consumerConfigFromStep Additional consumer configuration properties
     * @return The created listener, or null if creation failed
     */
    public DynamicKafkaListener createListener(
            String pipelineName,
            String stepName,
            String topic,
            String groupId,
            Map<String, String> consumerConfigFromStep) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String existingListenerId = pipelineStepToListenerMap.get(pipelineStepKey);

        if (existingListenerId != null) {
            DynamicKafkaListener existingListener = listenerPool.getListener(existingListenerId);
            if (existingListener != null) {
                log.info("Listener already exists for pipeline: {}, step: {}", pipelineName, stepName);
                return existingListener;
            }
        }
        String listenerId = generateListenerId(pipelineName, stepName);

        try {
            Map<String, Object> finalConsumerConfig = new HashMap<>(consumerConfigFromStep != null ? consumerConfigFromStep : Collections.emptyMap());

            // 1. Add Bootstrap Servers (Essential for any Kafka client)
            addBootstrapServers(finalConsumerConfig, listenerId);

            // 2. Add Schema Registry specific properties
            log.info("KafkaListenerManager (listener: {}): Configuring for schema registry type: '{}'", listenerId, configuredSchemaRegistryType);
            switch (configuredSchemaRegistryType) {
                case "apicurio":
                    addApicurioConsumerProperties(finalConsumerConfig, listenerId);
                    break;
                case "glue":
                    addGlueConsumerProperties(finalConsumerConfig, listenerId); // Placeholder
                    break;
                case "none":
                default:
                    log.warn("KafkaListenerManager (listener: {}): Schema registry type is '{}' or unknown. " +
                            "No specific schema registry properties will be added. " +
                            "Ensure deserializer is correctly configured if needed.", listenerId, configuredSchemaRegistryType);
                    // If no schema registry, but we expect Protobuf, we might default to a non-registry Protobuf deserializer.
                    // However, the value.deserializer might already be set in consumerConfigFromStep.
                    finalConsumerConfig.putIfAbsent(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            "io.micronaut.protobuf.serialize.ProtobufDeserializer"); // Example default
                    break;
            }

            log.debug("KafkaListenerManager (listener: {}): Final consumer config before passing to pool: {}", listenerId, finalConsumerConfig);

            DynamicKafkaListener listener = listenerPool.createListener(
                    listenerId, topic, groupId, finalConsumerConfig,
                    pipelineName, stepName, pipeStreamEngine);

            stateManager.updateState(listenerId, new ConsumerState(
                    listenerId, topic, groupId, false, Instant.now(), Collections.emptyMap()));
            pipelineStepToListenerMap.put(pipelineStepKey, listenerId);
            log.info("Created Kafka listener: {} for pipeline: {}, step: {}, topic: {}, group: {}",
                    listenerId, pipelineName, stepName, topic, groupId);
            return listener;
        } catch (Exception e) {
            log.error("Failed to create Kafka listener for pipeline: {}, step: {}, topic: {}, group: {}",
                    pipelineName, stepName, topic, groupId, e);
            return null;
        }
    }

    private void addBootstrapServers(Map<String, Object> consumerConfig, String listenerId) {
        String bootstrapServersPropKeyDefault = "kafka.consumers.default.bootstrap.servers";
        String bootstrapServersPropKeyGlobal = "kafka.bootstrap.servers";

        log.debug("KafkaListenerManager (listener: {}): Attempting to resolve bootstrap servers. Checking property: '{}'", listenerId, bootstrapServersPropKeyDefault);
        Optional<String> bootstrapServersFromDefaultConsumerPath = applicationContext.getProperty(bootstrapServersPropKeyDefault, String.class);
        log.debug("KafkaListenerManager (listener: {}): Value for '{}': '{}'", listenerId, bootstrapServersPropKeyDefault, bootstrapServersFromDefaultConsumerPath.orElse("NOT FOUND"));

        String resolvedBootstrapServers = bootstrapServersFromDefaultConsumerPath.orElseGet(() -> {
            log.warn("KafkaListenerManager (listener: {}): Could not find '{}', trying global '{}'", listenerId, bootstrapServersPropKeyDefault, bootstrapServersPropKeyGlobal);
            Optional<String> bootstrapServersFromGlobalPath = applicationContext.getProperty(bootstrapServersPropKeyGlobal, String.class);
            log.debug("KafkaListenerManager (listener: {}): Value for '{}': '{}'", listenerId, bootstrapServersPropKeyGlobal, bootstrapServersFromGlobalPath.orElse("NOT FOUND"));
            return bootstrapServersFromGlobalPath.orElse(null);
        });
        log.info("KafkaListenerManager (listener: {}): Final resolved bootstrap servers: '{}'", listenerId, resolvedBootstrapServers);

        if (resolvedBootstrapServers != null && !resolvedBootstrapServers.isBlank()) {
            consumerConfig.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolvedBootstrapServers);
            log.info("KafkaListenerManager (listener: {}): Added '{}' = '{}' to consumerConfig", listenerId, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, resolvedBootstrapServers);
        } else {
            log.error("KafkaListenerManager (listener: {}): CRITICAL - Bootstrap servers are null or blank. NOT ADDING TO CONFIG. KafkaConsumer will fail.", listenerId);
        }
    }

    private void addApicurioConsumerProperties(Map<String, Object> consumerConfig, String listenerId) {
        log.info("KafkaListenerManager (listener: {}): Adding Apicurio consumer properties.", listenerId);
        consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer");

        String urlPropKey = "kafka.consumers.default." + SerdeConfig.REGISTRY_URL;
        applicationContext.getProperty(urlPropKey, String.class).ifPresentOrElse(
                url -> {
                    consumerConfig.put(SerdeConfig.REGISTRY_URL, url);
                    log.info("KafkaListenerManager (listener: {}): Added Apicurio property '{}' = '{}'", listenerId, SerdeConfig.REGISTRY_URL, url);
                },
                () -> log.error("KafkaListenerManager (listener: {}): Apicurio property '{}' not found in application context.", listenerId, urlPropKey)
        );

        String returnClassPropKey = "kafka.consumers.default." + SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS;
        String returnClass = applicationContext.getProperty(returnClassPropKey, String.class).orElse(PipeStream.class.getName());
        consumerConfig.put(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);
        log.info("KafkaListenerManager (listener: {}): Added Apicurio property '{}' = '{}'", listenerId, SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS, returnClass);

        String strategyPropKey = "kafka.consumers.default." + SerdeConfig.ARTIFACT_RESOLVER_STRATEGY;
        String strategy = applicationContext.getProperty(strategyPropKey, String.class).orElse(io.apicurio.registry.serde.strategy.TopicIdStrategy.class.getName());
        consumerConfig.put(SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, strategy);
        log.info("KafkaListenerManager (listener: {}): Added Apicurio property '{}' = '{}'", listenerId, SerdeConfig.ARTIFACT_RESOLVER_STRATEGY, strategy);

        // Add other common Apicurio consumer properties if needed
        String groupIdPropKey = "kafka.consumers.default." + SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID;
        applicationContext.getProperty(groupIdPropKey, String.class).ifPresent( value -> {
            consumerConfig.put(SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, value);
            log.info("KafkaListenerManager (listener: {}): Added Apicurio property '{}' = '{}'", listenerId, SerdeConfig.EXPLICIT_ARTIFACT_GROUP_ID, value);
        });
    }

    private void addGlueConsumerProperties(Map<String, Object> consumerConfig, String listenerId) {
        log.warn("KafkaListenerManager (listener: {}): Placeholder for addGlueConsumerProperties. AWS Glue Schema Registry consumer configuration not yet implemented.", listenerId);
        // Example of what would go here:
        // consumerConfig.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "software.amazon.awssdk.services.glue.schemaRegistry.serde.GlueSchemaRegistryKafkaDeserializer");
        // String region = applicationContext.getProperty("aws.region", String.class).orElse("us-east-1"); // Example
        // String registryName = applicationContext.getProperty("aws.glue.schema.registry.name", String.class).orElse("default-registry"); // Example
        // consumerConfig.put(AWSSchemaRegistryConstants.AWS_REGION, region);
        // consumerConfig.put(AWSSchemaRegistryConstants.REGISTRY_NAME, registryName);
        // consumerConfig.put(AWSSchemaRegistryConstants.SCHEMA_NAME, topic + "-value"); // Or some other convention
        // consumerConfig.put(AWSSchemaRegistryConstants.DATA_FORMAT, DataFormat.PROTOBUF.name());
        // consumerConfig.put(AWSSchemaRegistryConstants.PROTOBUF_MESSAGE_TYPE, ProtobufMessageType.POJO.name());
        // consumerConfig.put(AWSSchemaRegistryConstants.CLASS_NAME_FOR_SPECIFIC_RETURN_TYPE, PipeStream.class.getName());
        // log.info("KafkaListenerManager (listener: {}): Added placeholder Glue properties.", listenerId);
    }

    // ... (rest of KafkaListenerManager methods: pauseConsumer, resumeConsumer, etc.)
    public CompletableFuture<Void> pauseConsumer(String pipelineName, String stepName) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.get(pipelineStepKey);

        if (listenerId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No listener found for pipeline: " + pipelineName +
                            ", step: " + stepName));
        }

        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listener ID found in mapping but not in pool: " + listenerId));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            listener.pause();
            stateManager.updateState(listenerId, new ConsumerState(
                    listenerId, listener.getTopic(), listener.getGroupId(),
                    true, Instant.now(), Collections.emptyMap()));
            result.complete(null);
            log.info("Paused Kafka consumer for pipeline: {}, step: {}, listener: {}",
                    pipelineName, stepName, listenerId);
        } catch (Exception e) {
            log.error("Failed to pause Kafka consumer for pipeline: {}, step: {}, listener: {}",
                    pipelineName, stepName, listenerId, e);
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Resumes a paused consumer for a specific pipeline step.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return A CompletableFuture that completes when the consumer is resumed
     */
    public CompletableFuture<Void> resumeConsumer(String pipelineName, String stepName) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.get(pipelineStepKey);

        if (listenerId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No listener found for pipeline: " + pipelineName +
                            ", step: " + stepName));
        }

        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listener ID found in mapping but not in pool: " + listenerId));
        }

        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            listener.resume();
            stateManager.updateState(listenerId, new ConsumerState(
                    listenerId, listener.getTopic(), listener.getGroupId(),
                    false, Instant.now(), Collections.emptyMap()));
            result.complete(null);
            log.info("Resumed Kafka consumer for pipeline: {}, step: {}, listener: {}",
                    pipelineName, stepName, listenerId);
        } catch (Exception e) {
            log.error("Failed to resume Kafka consumer for pipeline: {}, step: {}, listener: {}",
                    pipelineName, stepName, listenerId, e);
            result.completeExceptionally(e);
        }
        return result;
    }

    /**
     * Resets consumer offset to a specific date.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param date The date to reset to
     * @return A CompletableFuture that completes when the offset is reset
     */
    public CompletableFuture<Void> resetOffsetToDate(String pipelineName, String stepName, Instant date) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.get(pipelineStepKey);

        if (listenerId == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No listener found for pipeline: " + pipelineName +
                            ", step: " + stepName));
        }
        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listener ID found in mapping but not in pool: " + listenerId));
        }

        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.TO_TIMESTAMP)
                .timestamp(date.toEpochMilli())
                .build();
        log.info("Resetting Kafka consumer offset to date {} for pipeline: {}, step: {}, listener: {}",
                date, pipelineName, stepName, listenerId);

        CompletableFuture<Void> pauseFuture = pauseConsumer(pipelineName, stepName);
        return pauseFuture.thenCompose(v ->
                kafkaAdminService.resetConsumerGroupOffsetsAsync(
                        listener.getGroupId(), listener.getTopic(), params)
        ).thenCompose(v -> resumeConsumer(pipelineName, stepName));
    }

    /**
     * Resets consumer offset to earliest.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return A CompletableFuture that completes when the offset is reset
     */
    public CompletableFuture<Void> resetOffsetToEarliest(String pipelineName, String stepName) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.get(pipelineStepKey);
        if (listenerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No listener for " + pipelineName + ":" + stepName));
        }
        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Listener " + listenerId + " not in pool"));
        }
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.EARLIEST).build();
        log.info("Resetting offset to earliest for {}:{}:{}", pipelineName, stepName, listenerId);
        return pauseConsumer(pipelineName, stepName)
                .thenCompose(v -> kafkaAdminService.resetConsumerGroupOffsetsAsync(listener.getGroupId(), listener.getTopic(), params))
                .thenCompose(v -> resumeConsumer(pipelineName, stepName));
    }

    /**
     * Resets consumer offset to latest.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return A CompletableFuture that completes when the offset is reset
     */
    public CompletableFuture<Void> resetOffsetToLatest(String pipelineName, String stepName) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.get(pipelineStepKey);
        if (listenerId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No listener for " + pipelineName + ":" + stepName));
        }
        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Listener " + listenerId + " not in pool"));
        }
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.LATEST).build();
        log.info("Resetting offset to latest for {}:{}:{}", pipelineName, stepName, listenerId);
        return pauseConsumer(pipelineName, stepName)
                .thenCompose(v -> kafkaAdminService.resetConsumerGroupOffsetsAsync(listener.getGroupId(), listener.getTopic(), params))
                .thenCompose(v -> resumeConsumer(pipelineName, stepName));
    }

    /**
     * Gets the status of all consumers.
     *
     * @return A map of listener IDs to consumer statuses
     */
    public Map<String, ConsumerStatus> getConsumerStatuses() {
        Map<String, ConsumerStatus> statuses = new HashMap<>();
        for (DynamicKafkaListener listener : listenerPool.getAllListeners()) {
            ConsumerState state = stateManager.getState(listener.getListenerId());
            ConsumerStatus status = new ConsumerStatus(
                    listener.getListenerId(),
                    listener.getPipelineName(),
                    listener.getStepName(),
                    listener.getTopic(),
                    listener.getGroupId(),
                    listener.isPaused(),
                    state != null ? state.lastUpdated() : Instant.now()
            );
            statuses.put(listener.getListenerId(), status);
        }
        return statuses;
    }

    /**
     * Removes a listener for a specific pipeline step.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return true if the listener was removed, false otherwise
     */
    public boolean removeListener(String pipelineName, String stepName) {
        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String listenerId = pipelineStepToListenerMap.remove(pipelineStepKey);
        if (listenerId != null) {
            listenerPool.removeListener(listenerId);
            stateManager.removeState(listenerId);
            log.info("Removed Kafka listener for pipeline: {}, step: {}, listener: {}",
                    pipelineName, stepName, listenerId);
            return true;
        }
        log.warn("No listener found to remove for pipeline: {}, step: {}", pipelineName, stepName);
        return false;
    }

    /**
     * Generates a unique listener ID for a pipeline step.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The generated listener ID
     */
    private String generateListenerId(String pipelineName, String stepName) {
        return "kafka-listener-" + pipelineName + "-" + stepName + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Generates a key for the pipeline step map.
     *
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return The generated key
     */
    private String generatePipelineStepKey(String pipelineName, String stepName) {
        return pipelineName + ":" + stepName;
    }
}