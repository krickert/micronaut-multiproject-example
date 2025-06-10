// File: yappy-engine/src/main/java/com/krickert/search/pipeline/engine/kafka/listener/DynamicKafkaListener.java
package com.krickert.search.pipeline.engine.kafka.listener;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.apicurio.registry.serde.config.SerdeConfig; // Keep for logging
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * A dynamic Kafka consumer that can be created, paused, resumed, and shut down at runtime.
 * <br/>
 * This class is responsible for:
 * 1. Creating and managing a Kafka consumer with slot-based partition assignment
 * 2. Requesting and managing partition slots through KafkaSlotManager
 * 3. Polling for messages only from assigned partitions
 * 4. Maintaining slot ownership through heartbeats
 * 5. Processing messages by forwarding them to the PipeStreamEngine
 * 6. Supporting pause and resume operations
 * 7. Providing clean shutdown with slot release
 * <br/>
 * The DynamicKafkaListener runs in its own thread and integrates with KafkaSlotManager
 * for coordinated partition assignment across multiple engine instances.
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
    
    // Mandatory slot management
    private final KafkaSlotManager slotManager;
    private final String engineInstanceId;
    private final int requestedSlots;
    private Disposable slotWatcher;
    private volatile Set<Integer> assignedPartitions = Collections.emptySet();
    private volatile List<KafkaSlot> currentSlots = Collections.emptyList();
    private final Object partitionLock = new Object();
    private ScheduledExecutorService heartbeatExecutor;

    private KafkaConsumer<UUID, PipeStream> consumer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean pauseRequested = new AtomicBoolean(false);
    private final AtomicBoolean resumeRequested = new AtomicBoolean(false);
    private ExecutorService executorService;
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration SLOT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Constructor with mandatory slot management
     */
    public DynamicKafkaListener(
            String listenerId,
            String topic,
            String groupId,
            Map<String, Object> finalConsumerConfig, // Renamed for clarity: this is the fully prepared config
            Map<String, String> originalConsumerPropertiesFromStep, // NEW: Pass original properties
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine,
            KafkaSlotManager slotManager,
            String engineInstanceId,
            int requestedSlots) {

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
        
        // Slot management is now mandatory
        this.slotManager = Objects.requireNonNull(slotManager, "KafkaSlotManager cannot be null");
        this.engineInstanceId = Objects.requireNonNull(engineInstanceId, "Engine instance ID cannot be null");
        this.requestedSlots = Math.max(requestedSlots, 0); // 0 means as many as possible

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
        
        // Don't auto-subscribe in consumer config - we'll manage partitions manually
        this.consumerConfig.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        
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
        // Create consumer but don't subscribe yet - we need slots first
        consumer = new KafkaConsumer<>(this.consumerConfig);
        
        // Request slots before starting
        try {
            log.info("Listener {}: Requesting {} slots for topic: {}, group: {}", 
                    listenerId, requestedSlots == 0 ? "all available" : requestedSlots, topic, groupId);
            
            SlotAssignment assignment = slotManager.acquireSlots(engineInstanceId, topic, groupId, requestedSlots)
                    .block(SLOT_REQUEST_TIMEOUT);
            
            if (assignment == null || assignment.isEmpty()) {
                log.warn("Listener {}: No slots available for topic: {}, group: {}. Will watch for assignments.",
                        listenerId, topic, groupId);
                currentSlots = Collections.emptyList();
                assignedPartitions = Collections.emptySet();
            } else {
                handleSlotAssignment(assignment);
            }
            
            // Set up slot watcher for dynamic reassignment
            setupSlotWatcher();
            
            // Set up heartbeat executor
            heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactoryBuilder()
                            .setNameFormat("kafka-listener-heartbeat-" + listenerId + "-%d")
                            .setDaemon(true)
                            .build());
            
            // Schedule heartbeat task
            heartbeatExecutor.scheduleWithFixedDelay(
                    this::sendHeartbeat,
                    HEARTBEAT_INTERVAL.toSeconds(),
                    HEARTBEAT_INTERVAL.toSeconds(),
                    TimeUnit.SECONDS
            );
            
        } catch (Exception e) {
            log.error("Listener {}: Failed to acquire initial slots", listenerId, e);
            throw new RuntimeException("Failed to initialize listener with slot management", e);
        }

        executorService = Executors.newSingleThreadExecutor(
                new ThreadFactoryBuilder()
                        .setNameFormat("kafka-listener-" + listenerId + "-%d")
                        .setDaemon(true)
                        .build());

        running.set(true);
        executorService.submit(this::pollLoop);

        log.info("Initialized Kafka listener: {} for topic: {}, group: {} with {} assigned partitions",
                listenerId, topic, groupId, assignedPartitions.size());
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                // Check if we have assigned partitions
                synchronized (partitionLock) {
                    if (assignedPartitions.isEmpty()) {
                        log.debug("Listener {}: No partitions assigned, waiting for slot assignment", listenerId);
                        Thread.sleep(1000); // Wait for slot assignment
                        continue;
                    }
                }
                
                // Check if pause was requested
                if (pauseRequested.compareAndSet(true, false)) {
                    try {
                        Set<TopicPartition> partitions = consumer.assignment();
                        if (!partitions.isEmpty()) {
                            consumer.pause(partitions);
                            paused.set(true);
                            log.info("Paused Kafka listener: {}", listenerId);
                        } else {
                            log.warn("Kafka listener {} has no assigned partitions to pause.", listenerId);
                        }
                    } catch (IllegalStateException e) {
                        log.warn("Kafka listener {} could not be paused: {}", listenerId, e.getMessage());
                    }
                }

                // Check if resume was requested
                if (resumeRequested.compareAndSet(true, false)) {
                    try {
                        Set<TopicPartition> partitions = consumer.assignment();
                        if (!partitions.isEmpty()) {
                            consumer.resume(partitions);
                            paused.set(false);
                            log.info("Resumed Kafka listener: {}", listenerId);
                        } else {
                            log.warn("Kafka listener {} has no assigned partitions to resume.", listenerId);
                        }
                    } catch (IllegalStateException e) {
                        log.warn("Kafka listener {} could not be resumed: {}", listenerId, e.getMessage());
                    }
                }

                if (!paused.get()) {
                    log.debug("Listener {} polling for records from {} partitions", listenerId, assignedPartitions.size());
                    ConsumerRecords<UUID, PipeStream> records = consumer.poll(Duration.ofMillis(100));
                    
                    // Only process records from our assigned partitions
                    int processedCount = 0;
                    for (ConsumerRecord<UUID, PipeStream> record : records) {
                        synchronized (partitionLock) {
                            if (!assignedPartitions.contains(record.partition())) {
                                log.warn("Listener {}: Received record from unassigned partition {}. Skipping.",
                                        listenerId, record.partition());
                                continue;
                            }
                        }
                        
                        try {
                            log.debug("Listener {} processing record from partition {} with streamId: {}", 
                                    listenerId, record.partition(),
                                    record.value() != null ? record.value().getStreamId() : "null");
                            processRecord(record);
                            processedCount++;
                        } catch (Exception e) {
                            log.error("Error processing record from topic {}, partition {}, offset {}: {}",
                                    record.topic(), record.partition(), record.offset(), e.getMessage(), e);
                        }
                    }
                    
                    if (processedCount > 0) {
                        log.debug("Listener {} processed {} records", listenerId, processedCount);
                        // Commit offsets after processing
                        consumer.commitAsync((offsets, exception) -> {
                            if (exception != null) {
                                log.error("Listener {}: Failed to commit offsets", listenerId, exception);
                            }
                        });
                    }
                } else {
                    log.debug("Listener {} is paused, not polling for records", listenerId);
                    Thread.sleep(100); //NOSONAR
                }
            }
        } catch (InterruptedException e) {
            log.warn("Kafka listener {} poll loop interrupted.", listenerId);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Error in Kafka consumer {} poll loop: {}", listenerId, e.getMessage(), e);
        } finally {
            try {
                if (consumer != null) {
                    consumer.close(Duration.ofSeconds(5));
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
     * This method stops the polling thread, releases slots, and closes the consumer.
     */
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down Kafka listener: {}", listenerId);
            
            // Stop slot watcher
            if (slotWatcher != null && !slotWatcher.isDisposed()) {
                slotWatcher.dispose();
            }
            
            // Stop heartbeat executor
            if (heartbeatExecutor != null) {
                heartbeatExecutor.shutdown();
                try {
                    if (!heartbeatExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        heartbeatExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    heartbeatExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            // Release slots
            synchronized (partitionLock) {
                if (!currentSlots.isEmpty()) {
                    try {
                        log.info("Listener {}: Releasing {} slots", listenerId, currentSlots.size());
                        slotManager.releaseSlots(engineInstanceId, currentSlots)
                                .block(Duration.ofSeconds(5));
                    } catch (Exception e) {
                        log.error("Listener {}: Error releasing slots during shutdown", listenerId, e);
                    }
                    currentSlots = Collections.emptyList();
                    assignedPartitions = Collections.emptySet();
                }
            }
            
            // Shutdown poll loop executor
            if (executorService != null) {
                executorService.shutdown();
                try {
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                        log.warn("Executor service for listener {} did not terminate gracefully", listenerId);
                    }
                } catch (InterruptedException e) {
                    executorService.shutdownNow();
                    Thread.currentThread().interrupt();
                    log.warn("Interrupted while waiting for executor service of listener {} to terminate.", listenerId);
                }
            }
            
            log.info("Kafka listener {} shutdown complete.", listenerId);
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
    
    /**
     * Gets the currently assigned partitions.
     *
     * @return Set of assigned partition numbers
     */
    public Set<Integer> getAssignedPartitions() {
        synchronized (partitionLock) {
            return new HashSet<>(assignedPartitions);
        }
    }
    
    /**
     * Gets the current slot count.
     *
     * @return Number of assigned slots
     */
    public int getSlotCount() {
        synchronized (partitionLock) {
            return currentSlots.size();
        }
    }
    
    /**
     * Handle slot assignment changes.
     */
    private void handleSlotAssignment(SlotAssignment assignment) {
        synchronized (partitionLock) {
            List<KafkaSlot> topicSlots = assignment.getSlotsForTopic(topic);
            if (topicSlots.isEmpty()) {
                log.warn("Listener {}: No slots assigned for topic {}", listenerId, topic);
                currentSlots = Collections.emptyList();
                assignedPartitions = Collections.emptySet();
                // Unassign from consumer
                consumer.assign(Collections.emptyList());
                return;
            }
            
            currentSlots = new ArrayList<>(topicSlots);
            Set<Integer> newPartitions = topicSlots.stream()
                    .map(KafkaSlot::getPartition)
                    .collect(Collectors.toSet());
            
            if (!newPartitions.equals(assignedPartitions)) {
                log.info("Listener {}: Partition assignment changed from {} to {}", 
                        listenerId, assignedPartitions, newPartitions);
                
                assignedPartitions = newPartitions;
                
                // Update consumer assignment
                List<TopicPartition> topicPartitions = newPartitions.stream()
                        .map(partition -> new TopicPartition(topic, partition))
                        .collect(Collectors.toList());
                
                consumer.assign(topicPartitions);
                log.info("Listener {}: Consumer assigned to partitions: {}", listenerId, topicPartitions);
            }
        }
    }
    
    /**
     * Set up watcher for slot assignment changes.
     */
    private void setupSlotWatcher() {
        slotWatcher = slotManager.watchAssignments(engineInstanceId)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        this::handleSlotAssignment,
                        error -> log.error("Listener {}: Error watching slot assignments", listenerId, error),
                        () -> log.info("Listener {}: Slot watcher completed", listenerId)
                );
    }
    
    /**
     * Send heartbeat for assigned slots.
     */
    private void sendHeartbeat() {
        synchronized (partitionLock) {
            if (currentSlots.isEmpty()) {
                return;
            }
            
            try {
                log.debug("Listener {}: Sending heartbeat for {} slots", listenerId, currentSlots.size());
                slotManager.heartbeatSlots(engineInstanceId, currentSlots)
                        .doOnError(error -> log.error("Listener {}: Failed to send heartbeat", listenerId, error))
                        .subscribe();
            } catch (Exception e) {
                log.error("Listener {}: Error sending heartbeat", listenerId, e);
            }
        }
    }
}