package com.krickert.search.engine.kafka;

import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.configuration.kafka.ConsumerAware;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.OffsetStrategy;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Default implementation of KafkaConsumerService that manages Kafka consumers 
 * using partition slot assignments from KafkaSlotManager.
 * This allows for sticky partition assignments that survive restarts and provides better
 * control over which engine instance processes which partitions.
 */
@Singleton
@Requires(property = "app.kafka.slot-management.enabled", value = "true")
public class KafkaConsumerServiceImpl implements KafkaConsumerService {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerServiceImpl.class);
    
    private final KafkaSlotManager slotManager;
    private final KafkaConsumerConfiguration configuration;
    private final KafkaMessageProcessor messageProcessor;
    private final String engineInstanceId;
    private final int maxSlotsPerEngine;
    private final Duration heartbeatInterval;
    
    // Track active consumers by topic/group
    private final Map<String, ManagedKafkaConsumer> activeConsumers = new ConcurrentHashMap<>();
    
    // Track current slot assignments
    private volatile SlotAssignment currentAssignment = null;
    
    // Control flags
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean rebalancing = new AtomicBoolean(false);
    
    public KafkaConsumerServiceImpl(
            KafkaSlotManager slotManager,
            KafkaConsumerConfiguration configuration,
            KafkaMessageProcessor messageProcessor,
            @Value("${micronaut.application.instance-id:engine-${random.uuid}}") String engineInstanceId,
            @Value("${app.kafka.slot-management.max-slots-per-engine:100}") int maxSlotsPerEngine,
            @Value("${app.kafka.slot-management.consul.heartbeat-interval:30s}") Duration heartbeatInterval) {
        this.slotManager = slotManager;
        this.configuration = configuration;
        this.messageProcessor = messageProcessor;
        this.engineInstanceId = engineInstanceId;
        this.maxSlotsPerEngine = maxSlotsPerEngine;
        this.heartbeatInterval = heartbeatInterval;
    }
    
    @PostConstruct
    public void start() {
        LOG.info("Starting KafkaConsumerService for engine instance: {}", engineInstanceId);
        running.set(true);
        
        // Register this engine with the slot manager
        slotManager.registerEngine(engineInstanceId, maxSlotsPerEngine)
                .doOnSuccess(v -> LOG.info("Successfully registered engine {} with slot manager", engineInstanceId))
                .doOnError(e -> LOG.error("Failed to register engine with slot manager", e))
                .subscribe();
        
        // Start watching for assignment changes
        watchAssignmentChanges();
    }
    
    @PreDestroy
    public void stop() {
        LOG.info("Stopping KafkaConsumerService for engine instance: {}", engineInstanceId);
        running.set(false);
        
        // Stop all consumers
        activeConsumers.values().forEach(ManagedKafkaConsumer::stop);
        activeConsumers.clear();
        
        // Release all slots and unregister
        if (currentAssignment != null && !currentAssignment.isEmpty()) {
            slotManager.releaseSlots(engineInstanceId, currentAssignment.assignedSlots())
                    .then(slotManager.unregisterEngine(engineInstanceId))
                    .doOnSuccess(v -> LOG.info("Successfully unregistered engine and released slots"))
                    .doOnError(e -> LOG.error("Error during shutdown", e))
                    .block(Duration.ofSeconds(30));
        }
    }
    
    /**
     * Request slots for a specific topic and consumer group.
     * 
     * @param topic Kafka topic
     * @param groupId Consumer group ID
     * @param requestedSlots Number of slots requested (0 = as many as possible)
     * @return Mono indicating completion
     */
    public Mono<Void> requestSlots(String topic, String groupId, int requestedSlots) {
        LOG.info("Requesting {} slots for topic: {}, group: {}", requestedSlots, topic, groupId);
        
        return slotManager.acquireSlots(engineInstanceId, topic, groupId, requestedSlots)
                .doOnSuccess(assignment -> {
                    LOG.info("Acquired {} slots for topic {}", assignment.getSlotCount(), topic);
                    handleAssignmentUpdate(assignment);
                })
                .then();
    }
    
    /**
     * Release slots for a specific topic.
     * 
     * @param topic Topic to release slots for
     * @return Mono indicating completion
     */
    public Mono<Void> releaseSlots(String topic) {
        if (currentAssignment == null) {
            return Mono.empty();
        }
        
        List<KafkaSlot> slotsToRelease = currentAssignment.getSlotsForTopic(topic);
        if (slotsToRelease.isEmpty()) {
            return Mono.empty();
        }
        
        LOG.info("Releasing {} slots for topic: {}", slotsToRelease.size(), topic);
        
        return slotManager.releaseSlots(engineInstanceId, slotsToRelease)
                .doOnSuccess(v -> {
                    // Stop consumer for this topic
                    String consumerKey = createConsumerKey(topic, slotsToRelease.get(0).getGroupId());
                    ManagedKafkaConsumer consumer = activeConsumers.remove(consumerKey);
                    if (consumer != null) {
                        consumer.stop();
                    }
                })
                .then();
    }
    
    /**
     * Send heartbeats for all assigned slots.
     * This is called periodically by the scheduler.
     */
    @Scheduled(fixedDelay = "${app.kafka.slot-management.consul.heartbeat-interval:30s}")
    public void sendHeartbeats() {
        if (!running.get() || currentAssignment == null || currentAssignment.isEmpty()) {
            return;
        }
        
        LOG.debug("Sending heartbeats for {} slots", currentAssignment.getSlotCount());
        
        slotManager.heartbeatSlots(engineInstanceId, currentAssignment.assignedSlots())
                .doOnSuccess(v -> LOG.debug("Successfully sent heartbeats"))
                .doOnError(e -> LOG.error("Failed to send heartbeats", e))
                .subscribe();
    }
    
    /**
     * Watch for changes in slot assignments.
     */
    private void watchAssignmentChanges() {
        slotManager.watchAssignments(engineInstanceId)
                .filter(assignment -> running.get())
                .doOnNext(assignment -> {
                    LOG.info("Received assignment update: {} slots", assignment.getSlotCount());
                    handleAssignmentUpdate(assignment);
                })
                .doOnError(e -> LOG.error("Error watching assignments", e))
                .retry()
                .subscribe();
    }
    
    /**
     * Handle updates to slot assignments.
     */
    private void handleAssignmentUpdate(SlotAssignment newAssignment) {
        if (rebalancing.compareAndSet(false, true)) {
            try {
                SlotAssignment oldAssignment = currentAssignment;
                currentAssignment = newAssignment;
                
                // Calculate changes
                Map<String, List<KafkaSlot>> oldSlotsByTopicGroup = groupSlotsByTopicAndGroup(
                        oldAssignment != null ? oldAssignment.assignedSlots() : List.of());
                Map<String, List<KafkaSlot>> newSlotsByTopicGroup = groupSlotsByTopicAndGroup(
                        newAssignment.assignedSlots());
                
                // Stop consumers for removed topics
                Set<String> removedTopics = new HashSet<>(oldSlotsByTopicGroup.keySet());
                removedTopics.removeAll(newSlotsByTopicGroup.keySet());
                removedTopics.forEach(key -> {
                    ManagedKafkaConsumer consumer = activeConsumers.remove(key);
                    if (consumer != null) {
                        LOG.info("Stopping consumer for removed topic: {}", key);
                        consumer.stop();
                    }
                });
                
                // Update or create consumers for current topics
                newSlotsByTopicGroup.forEach((key, slots) -> {
                    List<KafkaSlot> oldSlots = oldSlotsByTopicGroup.get(key);
                    
                    if (oldSlots == null || !samePartitions(oldSlots, slots)) {
                        // Partitions changed, recreate consumer
                        ManagedKafkaConsumer existingConsumer = activeConsumers.get(key);
                        if (existingConsumer != null) {
                            LOG.info("Partition assignment changed for {}, recreating consumer", key);
                            existingConsumer.stop();
                        }
                        
                        // Create new consumer
                        createAndStartConsumer(key, slots);
                    }
                });
                
            } finally {
                rebalancing.set(false);
            }
        }
    }
    
    /**
     * Create and start a managed Kafka consumer for the given slots.
     */
    private void createAndStartConsumer(String consumerKey, List<KafkaSlot> slots) {
        if (slots.isEmpty()) {
            return;
        }
        
        KafkaSlot firstSlot = slots.get(0);
        String topic = firstSlot.getTopic();
        String groupId = firstSlot.getGroupId();
        List<Integer> partitions = slots.stream()
                .map(KafkaSlot::getPartition)
                .sorted()
                .collect(Collectors.toList());
        
        LOG.info("Creating consumer for topic: {}, group: {}, partitions: {}", 
                topic, groupId, partitions);
        
        ManagedKafkaConsumer consumer = new ManagedKafkaConsumer(topic, groupId, partitions);
        activeConsumers.put(consumerKey, consumer);
        consumer.start();
    }
    
    /**
     * Group slots by topic and group ID for easier management.
     */
    private Map<String, List<KafkaSlot>> groupSlotsByTopicAndGroup(List<KafkaSlot> slots) {
        return slots.stream()
                .collect(Collectors.groupingBy(slot -> 
                        createConsumerKey(slot.getTopic(), slot.getGroupId())));
    }
    
    /**
     * Create a unique key for a topic/group combination.
     */
    private String createConsumerKey(String topic, String groupId) {
        return topic + ":" + groupId;
    }
    
    /**
     * Check if two slot lists have the same partitions.
     */
    private boolean samePartitions(List<KafkaSlot> slots1, List<KafkaSlot> slots2) {
        Set<Integer> partitions1 = slots1.stream()
                .map(KafkaSlot::getPartition)
                .collect(Collectors.toSet());
        Set<Integer> partitions2 = slots2.stream()
                .map(KafkaSlot::getPartition)
                .collect(Collectors.toSet());
        return partitions1.equals(partitions2);
    }
    
    /**
     * Get current slot assignment for this engine.
     */
    public SlotAssignment getCurrentAssignment() {
        return currentAssignment;
    }
    
    /**
     * Get active consumer count.
     */
    public int getActiveConsumerCount() {
        return activeConsumers.size();
    }
    
    /**
     * Check if the service is running.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Start all consumers.
     */
    public void startConsumers() {
        if (!running.get()) {
            start();
        }
    }
    
    /**
     * Stop all consumers.
     */
    public void stopConsumers() {
        if (running.get()) {
            stop();
        }
    }
    
    /**
     * Check if all consumers are healthy.
     * @return true if all consumers are active and running
     */
    public boolean isHealthy() {
        if (!running.get()) {
            return false;
        }
        
        // Check if we have the expected number of consumers
        if (currentAssignment == null || activeConsumers.isEmpty()) {
            return false;
        }
        
        // Check if all consumers are active
        return activeConsumers.values().stream()
                .allMatch(consumer -> consumer.active.get());
    }
    
    /**
     * Internal class to manage a Kafka consumer with specific partition assignments.
     */
    private class ManagedKafkaConsumer {
        private final String topic;
        private final String groupId;
        private final List<Integer> partitions;
        private final AtomicBoolean active = new AtomicBoolean(false);
        private Thread consumerThread;
        private volatile KafkaConsumer<String, byte[]> kafkaConsumer;
        
        public ManagedKafkaConsumer(String topic, String groupId, List<Integer> partitions) {
            this.topic = topic;
            this.groupId = groupId;
            this.partitions = new ArrayList<>(partitions);
        }
        
        public void start() {
            if (active.compareAndSet(false, true)) {
                // Notify processor that consumer is starting
                messageProcessor.onConsumerStart(topic, groupId, partitions);
                
                consumerThread = new Thread(this::run, 
                        "kafka-consumer-" + topic + "-" + groupId);
                consumerThread.setDaemon(true);
                consumerThread.start();
            }
        }
        
        public void stop() {
            active.set(false);
            if (kafkaConsumer != null) {
                kafkaConsumer.wakeup();
            }
            if (consumerThread != null) {
                try {
                    consumerThread.join(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        private void run() {
            Properties props = createConsumerProperties();
            
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
                this.kafkaConsumer = consumer;
                
                // Assign specific partitions
                List<TopicPartition> topicPartitions = partitions.stream()
                        .map(p -> new TopicPartition(topic, p))
                        .collect(Collectors.toList());
                
                consumer.assign(topicPartitions);
                LOG.info("Consumer assigned to partitions: {} for topic: {}", partitions, topic);
                
                while (active.get()) {
                    try {
                        ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(1000));
                        
                        if (!records.isEmpty()) {
                            processRecords(records);
                            consumer.commitAsync();
                        }
                        
                    } catch (WakeupException e) {
                        // Expected when stopping
                        if (active.get()) {
                            throw e;
                        }
                    }
                }
                
            } catch (Exception e) {
                LOG.error("Error in consumer for topic: {}", topic, e);
            } finally {
                this.kafkaConsumer = null;
                // Notify processor that consumer is stopping
                messageProcessor.onConsumerStop(topic, groupId);
                LOG.info("Consumer stopped for topic: {}, group: {}", topic, groupId);
            }
        }
        
        private Properties createConsumerProperties() {
            Properties props = new Properties();
            
            // Get bootstrap servers from configuration
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, configuration.getBootstrapServers());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            props.put(ConsumerConfig.CLIENT_ID_CONFIG, 
                    engineInstanceId + "-" + topic + "-" + groupId);
            
            // Use configuration settings
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, 
                    String.valueOf(configuration.isEnableAutoCommit()));
            if (configuration.isEnableAutoCommit()) {
                props.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG,
                        String.valueOf(configuration.getAutoCommitInterval().toMillis()));
            }
            
            // Serializers
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
                    "org.apache.kafka.common.serialization.StringDeserializer");
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                    "org.apache.kafka.common.serialization.ByteArrayDeserializer");
            
            // Performance and timeout settings from configuration
            props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 
                    String.valueOf(configuration.getMaxPollRecords()));
            props.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 
                    String.valueOf(configuration.getFetchMinBytes()));
            props.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 
                    String.valueOf(configuration.getFetchMaxWait().toMillis()));
            props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG,
                    String.valueOf(configuration.getMaxPollInterval().toMillis()));
            props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG,
                    String.valueOf(configuration.getSessionTimeout().toMillis()));
            props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG,
                    String.valueOf(configuration.getHeartbeatInterval().toMillis()));
            props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                    String.valueOf(configuration.getRequestTimeout().toMillis()));
            props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG,
                    configuration.getIsolationLevel());
            
            // Add any additional properties
            if (configuration.getAdditionalProperties() != null) {
                props.putAll(configuration.getAdditionalProperties());
            }
            
            return props;
        }
        
        private void processRecords(ConsumerRecords<String, byte[]> records) {
            LOG.debug("Processing {} records from topic: {}", records.count(), topic);
            
            // Convert to list for easier processing
            List<ConsumerRecord<String, byte[]>> recordList = new ArrayList<>();
            records.forEach(recordList::add);
            
            try {
                // Process through the message processor
                messageProcessor.processRecords(recordList, topic, groupId)
                        .doOnSuccess(v -> LOG.debug("Successfully processed {} records", recordList.size()))
                        .doOnError(error -> {
                            LOG.error("Error processing records", error);
                            
                            // Determine if we should pause based on error
                            boolean shouldContinue = messageProcessor.handleError(
                                    error, null, topic, groupId);
                            
                            if (!shouldContinue && active.get()) {
                                LOG.warn("Pausing consumer due to error");
                                kafkaConsumer.pause(kafkaConsumer.assignment());
                                
                                // Schedule resume after error pause duration
                                scheduleResume();
                            }
                        })
                        .block(Duration.ofSeconds(30)); // Block with timeout
                        
            } catch (Exception e) {
                LOG.error("Unexpected error processing records", e);
                // Consider pausing on unexpected errors
                if (configuration.isPauseOnError() && active.get()) {
                    kafkaConsumer.pause(kafkaConsumer.assignment());
                    scheduleResume();
                }
            }
        }
        
        private void scheduleResume() {
            // Schedule resume after configured pause duration
            Thread resumeThread = new Thread(() -> {
                try {
                    Thread.sleep(configuration.getErrorPauseDuration().toMillis());
                    if (active.get() && kafkaConsumer != null) {
                        LOG.info("Resuming consumer after error pause");
                        kafkaConsumer.resume(kafkaConsumer.assignment());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-resume-" + topic);
            resumeThread.setDaemon(true);
            resumeThread.start();
        }
    }
}