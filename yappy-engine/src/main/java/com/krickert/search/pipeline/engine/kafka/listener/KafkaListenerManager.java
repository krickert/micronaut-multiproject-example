package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.search.pipeline.engine.kafka.admin.KafkaAdminService;
import com.krickert.search.pipeline.engine.kafka.admin.OffsetResetParameters;
import com.krickert.search.pipeline.engine.kafka.admin.OffsetResetStrategy;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
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
            PipeStreamEngine pipeStreamEngine) {
        this.listenerPool = listenerPool;
        this.stateManager = stateManager;
        this.kafkaAdminService = kafkaAdminService;
        this.configManager = configManager;
        this.pipeStreamEngine = pipeStreamEngine;
    }

    /**
     * Creates Kafka listeners for a pipeline based on configuration.
     * 
     * @param pipelineName The name of the pipeline
     * @return A list of created listener IDs
     */
    public List<String> createListenersForPipeline(String pipelineName) {
        log.info("Creating Kafka listeners for pipeline: {}", pipelineName);
        List<String> createdListeners = new ArrayList<>();

        Optional<PipelineConfig> pipelineConfig = configManager.getPipelineConfig(pipelineName);
        if (pipelineConfig.isEmpty()) {
            log.warn("Cannot create listeners for non-existent pipeline: {}", pipelineName);
            return createdListeners;
        }

        // Iterate through pipeline steps to find Kafka inputs
        pipelineConfig.get().pipelineSteps().forEach((stepName, stepConfig) -> {
            // Check if the step has Kafka inputs
            if (stepConfig.kafkaInputs() != null && !stepConfig.kafkaInputs().isEmpty()) {
                // Process each Kafka input definition
                for (KafkaInputDefinition kafkaInput : stepConfig.kafkaInputs()) {
                    // Process each topic in the listen topics list
                    for (String topic : kafkaInput.listenTopics()) {
                        String groupId = kafkaInput.consumerGroupId();
                        if (groupId == null || groupId.isBlank()) {
                            // Generate a default group ID if not provided
                            groupId = "yappy-" + pipelineName + "-" + stepName + "-group";
                        }

                        // Create a listener for this topic and group
                        DynamicKafkaListener listener = createListener(
                                pipelineName, 
                                stepName, 
                                topic, 
                                groupId, 
                                kafkaInput.kafkaConsumerProperties());

                        if (listener != null) {
                            createdListeners.add(listener.getListenerId());
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
     * @param consumerConfig Additional consumer configuration properties
     * @return The created listener, or null if creation failed
     */
    public DynamicKafkaListener createListener(
            String pipelineName,
            String stepName, 
            String topic, 
            String groupId, 
            Map<String, String> consumerConfig) {

        String pipelineStepKey = generatePipelineStepKey(pipelineName, stepName);
        String existingListenerId = pipelineStepToListenerMap.get(pipelineStepKey);

        // Check if listener already exists
        if (existingListenerId != null) {
            DynamicKafkaListener existingListener = listenerPool.getListener(existingListenerId);
            if (existingListener != null) {
                log.info("Listener already exists for pipeline: {}, step: {}", pipelineName, stepName);
                return existingListener;
            }
        }

        // Generate a unique listener ID
        String listenerId = generateListenerId(pipelineName, stepName);

        try {
            // Create new listener
            DynamicKafkaListener listener = listenerPool.createListener(
                    listenerId, topic, groupId, consumerConfig, pipelineName, stepName, pipeStreamEngine);

            // Update state
            stateManager.updateState(listenerId, new ConsumerState(
                    listenerId, topic, groupId, false, Instant.now(), Collections.emptyMap()));

            // Update mapping
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

    /**
     * Pauses a consumer for a specific pipeline step.
     * 
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @return A CompletableFuture that completes when the consumer is paused
     */
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

        // Create offset reset parameters for the timestamp
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.TO_TIMESTAMP)
                .timestamp(date.toEpochMilli())
                .build();

        log.info("Resetting Kafka consumer offset to date {} for pipeline: {}, step: {}, listener: {}", 
                date, pipelineName, stepName, listenerId);

        // Pause the consumer first
        CompletableFuture<Void> pauseFuture = pauseConsumer(pipelineName, stepName);

        // Then reset the offset
        return pauseFuture.thenCompose(v -> 
            kafkaAdminService.resetConsumerGroupOffsetsAsync(
                    listener.getGroupId(), listener.getTopic(), params)
        ).thenCompose(v -> {
            // Resume the consumer after reset
            return resumeConsumer(pipelineName, stepName);
        });
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
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No listener found for pipeline: " + pipelineName + 
                                                ", step: " + stepName));
        }

        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listener ID found in mapping but not in pool: " + listenerId));
        }

        // Create offset reset parameters for earliest
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.EARLIEST)
                .build();

        log.info("Resetting Kafka consumer offset to earliest for pipeline: {}, step: {}, listener: {}", 
                pipelineName, stepName, listenerId);

        // Pause the consumer first
        CompletableFuture<Void> pauseFuture = pauseConsumer(pipelineName, stepName);

        // Then reset the offset
        return pauseFuture.thenCompose(v -> 
            kafkaAdminService.resetConsumerGroupOffsetsAsync(
                    listener.getGroupId(), listener.getTopic(), params)
        ).thenCompose(v -> {
            // Resume the consumer after reset
            return resumeConsumer(pipelineName, stepName);
        });
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
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No listener found for pipeline: " + pipelineName + 
                                                ", step: " + stepName));
        }

        DynamicKafkaListener listener = listenerPool.getListener(listenerId);
        if (listener == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Listener ID found in mapping but not in pool: " + listenerId));
        }

        // Create offset reset parameters for latest
        OffsetResetParameters params = OffsetResetParameters.builder(OffsetResetStrategy.LATEST)
                .build();

        log.info("Resetting Kafka consumer offset to latest for pipeline: {}, step: {}, listener: {}", 
                pipelineName, stepName, listenerId);

        // Pause the consumer first
        CompletableFuture<Void> pauseFuture = pauseConsumer(pipelineName, stepName);

        // Then reset the offset
        return pauseFuture.thenCompose(v -> 
            kafkaAdminService.resetConsumerGroupOffsetsAsync(
                    listener.getGroupId(), listener.getTopic(), params)
        ).thenCompose(v -> {
            // Resume the consumer after reset
            return resumeConsumer(pipelineName, stepName);
        });
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
