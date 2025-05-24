// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/DynamicKafkaListener.java
package com.krickert.search.pipeline.engine.kafka.listener;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.apicurio.registry.serde.config.SerdeConfig; // Keep for logging
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A dynamic Kafka consumer that can be created, paused, resumed, and shut down at runtime.
 * <br/>
 * This class is responsible for:
 * 1. Creating and managing a Kafka consumer
 * 2. Polling for messages from a Kafka topic
 * 3. Processing messages by forwarding them to the PipeStreamEngine
 * 4. Supporting pause and resume operations
 * 5. Providing clean shutdown
 * <br/>
 * The DynamicKafkaListener runs in its own thread and can be paused and resumed
 * without stopping the thread.
 */
@SuppressWarnings("LombokGetterMayBeUsed")
public class DynamicKafkaListener {
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaListener.class);

    private final String listenerId;
    private final String topic;
    private final String groupId;
    private final Map<String, Object> consumerConfig; // This is the final, fully prepared config
    private final Map<String, String> originalConsumerProperties; // NEW: Stores properties from KafkaInputDefinition
    private final String pipelineName;
    private final String stepName;
    private final PipeStreamEngine pipeStreamEngine;

    private KafkaConsumer<UUID, PipeStream> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean resumeRequested = new AtomicBoolean(false);
    private ExecutorService executorService;

    public DynamicKafkaListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig, // Renamed for clarity: this is the fully prepared config
            Map<String, String> originalConsumerPropertiesFromStep, // NEW: Pass original properties
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine) {

        this.listenerId = Objects.requireNonNull(listenerId, "Listener ID cannot be null");
        this.topic = Objects.requireNonNull(topic, "Topic cannot be null");
        this.groupId = Objects.requireNonNull(groupId, "Group ID cannot be null");
        this.consumerConfig = new HashMap<>(Objects.requireNonNull(finalConsumerConfig, "Final consumer config cannot be null"));
        // Store a copy of the original properties for comparison purposes
        this.originalConsumerProperties = (originalConsumerPropertiesFromStep == null)
                ? Collections.emptyMap()
                : new HashMap<>(originalConsumerPropertiesFromStep);
        this.pipelineName = Objects.requireNonNull(pipelineName, "Pipeline name cannot be null");
        this.stepName = Objects.requireNonNull(stepName, "Step name cannot be null");
        this.pipeStreamEngine = Objects.requireNonNull(pipeStreamEngine, "PipeStreamEngine cannot be null");

        // Essential Kafka properties that are not schema-registry specific
        // These should have been added by KafkaListenerManager to finalConsumerConfig
        // but we can ensure they are present or log if not.
        if (!this.consumerConfig.containsKey(ConsumerConfig.GROUP_ID_CONFIG) ||
                !groupId.equals(this.consumerConfig.get(ConsumerConfig.GROUP_ID_CONFIG))) {
            log.warn("Listener {}: Group ID in finalConsumerConfig ('{}') does not match provided groupId ('{}'). Using value from finalConsumerConfig.",
                    listenerId, this.consumerConfig.get(ConsumerConfig.GROUP_ID_CONFIG), groupId);
        }
        this.consumerConfig.putIfAbsent(ConsumerConfig.GROUP_ID_CONFIG, groupId); // Ensure it's there

        if (!this.consumerConfig.containsKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)) {
            log.warn("Listener {}: Consumer property '{}' is missing. Defaulting to UUIDDeserializer, but this should be set by KafkaListenerManager.",
                    listenerId, ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG);
            this.consumerConfig.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.UUIDDeserializer");
        }


        // VALUE_DESERIALIZER_CLASS_CONFIG and schema registry properties (like apicurio.registry.url)
        // are now expected to be pre-populated in the 'finalConsumerConfig' map by KafkaListenerManager.

        String valueDeserializerClass = (String) this.consumerConfig.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        if (valueDeserializerClass == null || valueDeserializerClass.isBlank()) {
            log.error("Listener {}: CRITICAL - Consumer property '{}' is missing or blank in the provided config. Deserialization will fail.",
                    listenerId, ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG);
        } else {
            log.info("Listener {}: Using value deserializer: {}", listenerId, valueDeserializerClass);
            if ("io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer".equals(valueDeserializerClass)) {
                if (!this.consumerConfig.containsKey(SerdeConfig.REGISTRY_URL)) {
                    log.warn("Listener {}: Apicurio deserializer is configured, but registry URL ('{}') not found in consumerConfig. Deserialization might fail.",
                            listenerId, SerdeConfig.REGISTRY_URL);
                }
                if (!this.consumerConfig.containsKey(SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS)) {
                    log.warn("Listener {}: Apicurio deserializer is configured, but specific value return class ('{}') not found in consumerConfig. Defaulting might occur or deserialization might fail.",
                            listenerId, SerdeConfig.DESERIALIZER_SPECIFIC_VALUE_RETURN_CLASS);
                }
            }
            // TODO: Add similar checks if valueDeserializerClass is for Glue
        }
        if (!this.consumerConfig.containsKey(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)) {
            log.error("Listener {}: CRITICAL - Consumer property '{}' is missing or blank. Consumer will fail to connect.",
                    listenerId, ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
        }


        log.debug("Listener {}: Final consumer config being passed to KafkaConsumer: {}", listenerId, this.consumerConfig);
        initialize();
    }

    /**
     * Returns the original consumer properties that were provided from the
     * KafkaInputDefinition, before any defaults or manager-added properties were merged.
     * This is used by KafkaListenerManager to compare if the user-defined part of the
     * configuration has changed.
     *
     * @return A map of the original consumer properties.
     */
    public Map<String, Object> getConsumerConfigForComparison() {
        // Convert Map<String, String> to Map<String, Object> for compatibility
        // with areConsumerPropertiesEqual in KafkaListenerManager
        return new HashMap<>(this.originalConsumerProperties);
    }

    private void initialize() {
        consumer = new KafkaConsumer<>(this.consumerConfig);
        consumer.subscribe(Collections.singletonList(topic));

        executorService = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat("kafka-listener-" + listenerId + "-%d") // More specific name
                        .setDaemon(true)
                        .build());

        running.set(true);
        executorService.submit(this::pollLoop);

        log.info("Initialized Kafka listener: {} for topic: {}, group: {}",
                listenerId, topic, groupId);
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                // Check if pause was requested
                if (pauseRequested.compareAndSet(true, false)) {
                    try {
                        Set<TopicPartition> partitions = consumer.assignment();
                        if (!partitions.isEmpty()) {
                            consumer.pause(partitions);
                            // Set the paused flag to true after actually pausing the consumer
                            paused.set(true);
                            log.info("Paused Kafka listener: {}", listenerId);
                        } else {
                            log.warn("Kafka listener {} has no assigned partitions to pause.", listenerId);
                        }
                    } catch (IllegalStateException e) {
                        log.warn("Kafka listener {} could not be paused, possibly not subscribed or consumer closed: {}", listenerId, e.getMessage());
                    }
                }

                // Check if resume was requested
                if (resumeRequested.compareAndSet(true, false)) {
                    try {
                        Set<TopicPartition> partitions = consumer.assignment();
                        if (!partitions.isEmpty()) {
                            consumer.resume(partitions);
                            // Set the paused flag to false after actually resuming the consumer
                            paused.set(false);
                            log.info("Resumed Kafka listener: {}", listenerId);
                        } else {
                            log.warn("Kafka listener {} has no assigned partitions to resume.", listenerId);
                        }
                    } catch (IllegalStateException e) {
                        log.warn("Kafka listener {} could not be resumed, possibly not subscribed or consumer closed: {}", listenerId, e.getMessage());
                    }
                }

                if (!paused.get()) {
                    log.debug("Listener {} is not paused, polling for records", listenerId);
                    ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofMillis(100));
                    int recordCount = records.count();
                    if (recordCount > 0) {
                        log.debug("Listener {} received {} records", listenerId, recordCount);
                    }
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        try {
                            log.debug("Listener {} processing record with streamId: {}", listenerId,
                                    record.value() != null ? record.value().getStreamId() : "null");
                            processRecord(record);
                        } catch (Exception e) {
                            log.error("Error processing record from topic {}, partition {}, offset {}: {}",
                                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
                            // Consider how to handle individual record processing errors (e.g., DLQ)
                        }
                    }
                } else {
                    log.debug("Listener {} is paused, not polling for records", listenerId);
                    Thread.sleep(100); //NOSONAR
                }
            }
        } catch (InterruptedException e) {
            log.warn("Kafka listener {} poll loop interrupted.", listenerId);
            Thread.currentThread().interrupt();
        } catch (Exception e) { // Catch other potential exceptions from poll() or consumer operations
            log.error("Error in Kafka consumer {} poll loop: {}", listenerId, e.getMessage(), e);
            // Consider more robust error handling, e.g., attempting to re-initialize the consumer
        } finally {
            try {
                if (consumer != null) {
                    consumer.close(Duration.ofSeconds(5)); // Close with a timeout
                }
            } catch (Exception e) {
                log.error("Error closing Kafka consumer for listener {}: {}", listenerId, e.getMessage(), e);
            }
            log.info("Kafka listener {} poll loop finished.", listenerId);
        }
    }

    /**
     * Processes a single Kafka record by forwarding it to the PipeStreamEngine.
     * <br/>
     * This method acknowledges the message right after deserialization,
     * and then processes it asynchronously to ensure exactly-once processing
     * in a fan-in/fan-out system.
     *
     * @param record The Kafka record to process
     */
    private void processRecord(ConsumerRecord<UUID, PipeStream> record) {
        PipeStream pipeStream = record.value();
        if (pipeStream == null) {
            log.warn("Received null message from Kafka. Topic: {}, Partition: {}, Offset: {}. Skipping.",
                    record.topic(), record.partition(), record.offset());
            return;
        }

        log.debug("Listener {}: Received record from topic: {}, partition: {}, offset: {}",
                listenerId, record.topic(), record.partition(), record.offset());

        PipeStream updatedPipeStream = pipeStream.toBuilder()
                .setCurrentPipelineName(pipelineName)
                .setTargetStepName(stepName)
                .build();

        pipeStreamEngine.processStream(updatedPipeStream);

        log.debug("Listener {}: Forwarded record to PipeStreamEngine. StreamId: {}", listenerId, updatedPipeStream.getStreamId());
    }

    /**
     * Pauses the consumer.
     * This method pauses the consumer without stopping the polling thread.
     */
    public void pause() {
        // Only set the pauseRequested flag to true to signal the consumer thread
        // The paused flag will be set by the consumer thread after actually pausing
        if (!paused.get()) {
            pauseRequested.set(true);
            log.info("Pause requested for Kafka listener: {}", listenerId);
        }
    }

    /**
     * Resumes the consumer.
     * This method resumes a paused consumer.
     */
    public void resume() {
        // Only set the resumeRequested flag to true to signal the consumer thread
        // The paused flag will be set by the consumer thread after actually resuming
        if (paused.get()) {
            resumeRequested.set(true);
            log.info("Resume requested for Kafka listener: {}", listenerId);
        }
    }

    /**
     * Shuts down the consumer.
     * This method stops the polling thread and closes the consumer.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) { // Ensure shutdown logic runs only once
            log.info("Shutting down Kafka listener: {}", listenerId);
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                        log.warn("Executor service for listener {} did not terminate gracefully, forced shutdown.", listenerId);
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for executor service of listener {} to terminate.", listenerId);
                }
            }
            log.info("Kafka listener {} shutdown process initiated.", listenerId);
        }
    }

    /**
     * Checks if the consumer is paused.
     *
     * @return true if the consumer is paused, false otherwise
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Gets the ID of the listener.
     *
     * @return The listener ID
     */
    public String getListenerId() {
        return listenerId;
    }

    /**
     * Gets the topic the consumer is subscribed to.
     *
     * @return The topic
     */
    public String getTopic() {
        return topic;
    }

    /**
     * Gets the consumer group ID.
     *
     * @return The group ID
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Gets the name of the pipeline.
     *
     * @return The pipeline name
     */
    public String getPipelineName() {
        return pipelineName;
    }

    /**
     * Gets the name of the step.
     *
     * @return The step name
     */
    public String getStepName() {
        return stepName;
    }
}