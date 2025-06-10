package com.krickert.search.pipeline.engine.kafka.slot;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.KafkaInputDefinition;
import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.pipeline.engine.kafka.listener.DynamicKafkaListener;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerManager;
import com.krickert.search.pipeline.engine.kafka.listener.KafkaListenerPool;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerStateManager;
import com.krickert.search.pipeline.engine.kafka.listener.ConsumerState;
import com.krickert.search.pipeline.engine.PipeStreamEngine;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.scheduling.annotation.Async;
import io.micronaut.core.annotation.NonNull;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.time.Instant;

/**
 * Enhanced KafkaListenerManager that uses Consul-based slot management
 * to coordinate partition assignments across engine instances.
 * 
 * This replaces the default KafkaListenerManager when slot management is enabled.
 */
@Singleton
@Replaces(KafkaListenerManager.class)
@Requires(property = "app.kafka.slot-management.enabled", value = "true", defaultValue = "false")
public class SlotAwareKafkaListenerManager implements ApplicationEventListener<PipelineClusterConfigChangeEvent> {
    
    private static final Logger LOG = LoggerFactory.getLogger(SlotAwareKafkaListenerManager.class);
    
    private final KafkaListenerPool listenerPool;
    private final KafkaSlotManager slotManager;
    private final String engineInstanceId;
    private final String kafkaBootstrapServers;
    private final PipeStreamEngine pipeStreamEngine;
    private final Map<String, SlotAssignment> currentAssignments = new ConcurrentHashMap<>();
    private final Map<String, Flux<SlotAssignment>> assignmentWatchers = new ConcurrentHashMap<>();
    private final Map<String, DynamicKafkaListener> activeListeners = new ConcurrentHashMap<>();
    
    @Inject
    public SlotAwareKafkaListenerManager(
            KafkaListenerPool listenerPool,
            KafkaSlotManager slotManager,
            PipeStreamEngine pipeStreamEngine,
            @Value("${app.engine.instance-id:#{T(java.util.UUID).randomUUID().toString()}}") String engineInstanceId,
            @Value("${kafka.bootstrap.servers}") String kafkaBootstrapServers,
            @Value("${app.kafka.slot-management.max-slots-per-engine:10}") int maxSlotsPerEngine) {
        this.listenerPool = listenerPool;
        this.slotManager = slotManager;
        this.pipeStreamEngine = pipeStreamEngine;
        this.engineInstanceId = engineInstanceId;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        
        LOG.info("Initializing SlotAwareKafkaListenerManager with slot management enabled");
        
        // Register this engine with slot manager
        slotManager.registerEngine(engineInstanceId, maxSlotsPerEngine)
                .subscribe(
                        v -> LOG.info("Engine {} registered with slot manager", engineInstanceId),
                        error -> LOG.error("Failed to register engine with slot manager", error)
                );
    }
    
    @Override
    @Async
    public void onApplicationEvent(@NonNull PipelineClusterConfigChangeEvent event) {
        LOG.info("Received configuration change event for cluster: {}", event.clusterName());
        
        PipelineClusterConfig config = event.newConfig();
        if (config == null || config.pipelineGraphConfig() == null || config.pipelineGraphConfig().pipelines() == null) {
            LOG.warn("Received null or empty configuration");
            return;
        }
        
        // Process each pipeline with slot awareness
        for (Map.Entry<String, PipelineConfig> pipelineEntry : config.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();
            
            if (pipeline.pipelineSteps() == null) {
                continue;
            }
            
            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                String stepName = stepEntry.getKey();
                PipelineStepConfig step = stepEntry.getValue();
                
                if (shouldCreateListener(step)) {
                    String topic = extractTopicFromStep(step);
                    String groupId = generateGroupId(pipelineName, stepName);
                    
                    // Request slots for this topic
                    requestSlotsForTopic(topic, groupId, pipelineName, stepName, config);
                }
            }
        }
        
        // Clean up listeners for removed configurations
        cleanupRemovedListeners(config);
    }
    
    private void requestSlotsForTopic(String topic, String groupId, String pipelineName, 
                                    String stepName, PipelineClusterConfig config) {
        LOG.info("Requesting slots for topic {} group {}", topic, groupId);
        
        // Request slots (0 = as many as possible)
        slotManager.acquireSlots(engineInstanceId, topic, groupId, 0)
                .doOnSuccess(assignment -> {
                    LOG.info("Acquired {} slots for topic {}", assignment.getSlotCount(), topic);
                    currentAssignments.put(topic + ":" + groupId, assignment);
                    
                    // Create listeners for assigned partitions
                    updateListenersForAssignment(assignment, pipelineName, stepName, config);
                    
                    // Start watching for assignment changes
                    startWatchingAssignment(topic, groupId);
                })
                .doOnError(error -> LOG.error("Failed to acquire slots for topic {}", topic, error))
                .subscribe();
    }
    
    private void updateListenersForAssignment(SlotAssignment assignment, String pipelineName,
                                            String stepName, PipelineClusterConfig config) {
        String topic = assignment.assignedSlots().isEmpty() ? "" : 
                assignment.assignedSlots().get(0).getTopic();
        String groupId = assignment.assignedSlots().isEmpty() ? "" : 
                assignment.assignedSlots().get(0).getGroupId();
        
        // Get current listeners for this topic/group
        String baseListenerKey = pipelineName + ":" + stepName + ":" + topic + ":" + groupId;
        Set<String> existingListeners = getExistingListenersForBase(baseListenerKey);
        
        // Create partition-specific listeners for assigned slots
        List<Integer> assignedPartitions = assignment.getPartitionsForTopic(topic);
        
        for (int partition : assignedPartitions) {
            String listenerKey = baseListenerKey + ":p" + partition;
            
            if (!existingListeners.contains(listenerKey)) {
                // Create new listener for this partition
                Properties props = createKafkaProperties(config, groupId);
                
                // Force partition assignment
                props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId + "-" + engineInstanceId + "-p" + partition);
                
                DynamicKafkaListener listener = createListener(
                        listenerKey, topic, props, pipelineName, stepName, config
                );
                
                if (listener != null) {
                    // Note: Partition assignment would need to be handled through consumer configuration
                    // or by implementing a partition assignment strategy
                    activeListeners.put(listenerKey, listener);
                    LOG.info("Created listener for partition {} of topic {}", partition, topic);
                }
            }
        }
        
        // Remove listeners for partitions we no longer own
        for (String existingKey : existingListeners) {
            int partition = extractPartitionFromKey(existingKey);
            if (partition >= 0 && !assignedPartitions.contains(partition)) {
                removeListener(existingKey);
                LOG.info("Removed listener for partition {} of topic {}", partition, topic);
            }
        }
    }
    
    private void startWatchingAssignment(String topic, String groupId) {
        String watchKey = topic + ":" + groupId;
        
        if (!assignmentWatchers.containsKey(watchKey)) {
            Flux<SlotAssignment> watcher = slotManager.watchAssignments(engineInstanceId)
                    .filter(assignment -> assignment.getSlotsByTopic().containsKey(topic))
                    .doOnNext(assignment -> {
                        SlotAssignment current = currentAssignments.get(watchKey);
                        if (!assignment.equals(current)) {
                            LOG.info("Slot assignment changed for topic {}", topic);
                            currentAssignments.put(watchKey, assignment);
                            // Trigger reconfiguration
                            // Note: In real implementation, would need pipeline/step info
                        }
                    })
                    .doOnError(error -> LOG.error("Error watching assignments", error))
                    .retry()
                    .share();
            
            assignmentWatchers.put(watchKey, watcher);
            watcher.subscribe(); // Start watching
        }
    }
    
    private Set<String> getExistingListenersForBase(String baseKey) {
        return activeListeners.keySet().stream()
                .filter(id -> id.startsWith(baseKey))
                .collect(Collectors.toSet());
    }
    
    private int extractPartitionFromKey(String listenerKey) {
        int index = listenerKey.lastIndexOf(":p");
        if (index >= 0) {
            try {
                return Integer.parseInt(listenerKey.substring(index + 2));
            } catch (NumberFormatException e) {
                // Ignore
            }
        }
        return -1;
    }
    
    private boolean shouldCreateListener(PipelineStepConfig step) {
        // Only create listeners for steps with Kafka inputs
        return step.kafkaInputs() != null && !step.kafkaInputs().isEmpty();
    }
    
    private String extractTopicFromStep(PipelineStepConfig step) {
        if (step.kafkaInputs() == null || step.kafkaInputs().isEmpty()) {
            return null;
        }
        // For simplicity, use the first Kafka input's topic
        KafkaInputDefinition kafkaInput = step.kafkaInputs().get(0);
        return kafkaInput.listenTopics() != null && !kafkaInput.listenTopics().isEmpty() ? 
               kafkaInput.listenTopics().get(0) : null;
    }
    
    private String generateGroupId(String pipelineName, String stepName) {
        return pipelineName + "-" + stepName + "-consumer";
    }
    
    private Properties createKafkaProperties(PipelineClusterConfig config, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringDeserializer");
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, "io.micronaut.protobuf.serialize.ProtobufDeserializer");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }
    
    private DynamicKafkaListener createListener(String listenerKey, String topic, Properties props, 
                                              String pipelineName, String stepName, PipelineClusterConfig config) {
        try {
            Map<String, Object> consumerConfig = new HashMap<>();
            props.forEach((key, value) -> consumerConfig.put(key.toString(), value));
            
            return listenerPool.createListener(
                listenerKey,
                topic,
                props.getProperty(ConsumerConfig.GROUP_ID_CONFIG),
                consumerConfig,
                new HashMap<>(), // Original consumer properties from step
                pipelineName,
                stepName,
                pipeStreamEngine
            );
        } catch (Exception e) {
            LOG.error("Failed to create listener for key: {}", listenerKey, e);
            return null;
        }
    }
    
    private void removeListener(String listenerKey) {
        DynamicKafkaListener listener = activeListeners.remove(listenerKey);
        if (listener != null) {
            try {
                listener.shutdown();
                listenerPool.removeListener(listener.getListenerId());
                LOG.info("Removed listener: {}", listenerKey);
            } catch (Exception e) {
                LOG.error("Error removing listener: {}", listenerKey, e);
            }
        }
    }
    
    private void cleanupRemovedListeners(PipelineClusterConfig config) {
        // Remove listeners that are no longer in the configuration
        Set<String> currentKeys = new HashSet<>(activeListeners.keySet());
        Set<String> configuredKeys = extractConfiguredListenerKeys(config);
        
        for (String key : currentKeys) {
            if (!configuredKeys.contains(key)) {
                removeListener(key);
            }
        }
    }
    
    private Set<String> extractConfiguredListenerKeys(PipelineClusterConfig config) {
        Set<String> keys = new HashSet<>();
        if (config.pipelineGraphConfig() != null && config.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : config.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineName = pipelineEntry.getKey();
                PipelineConfig pipeline = pipelineEntry.getValue();
                
                if (pipeline.pipelineSteps() != null) {
                    for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                        String stepName = stepEntry.getKey();
                        PipelineStepConfig step = stepEntry.getValue();
                        
                        if (shouldCreateListener(step)) {
                            String topic = extractTopicFromStep(step);
                            String groupId = generateGroupId(pipelineName, stepName);
                            String key = pipelineName + ":" + stepName + ":" + topic + ":" + groupId;
                            keys.add(key);
                        }
                    }
                }
            }
        }
        return keys;
    }
    
    @PreDestroy
    public void shutdown() {
        LOG.info("Shutting down SlotAwareKafkaListenerManager");
        
        // Release all slots
        for (SlotAssignment assignment : currentAssignments.values()) {
            slotManager.releaseSlots(engineInstanceId, assignment.assignedSlots())
                    .subscribe(
                            v -> LOG.info("Released {} slots", assignment.getSlotCount()),
                            error -> LOG.error("Failed to release slots", error)
                    );
        }
        
        // Unregister engine
        slotManager.unregisterEngine(engineInstanceId)
                .subscribe(
                        v -> LOG.info("Unregistered engine from slot manager"),
                        error -> LOG.error("Failed to unregister engine", error)
                );
        
        // Shutdown all listeners
        for (DynamicKafkaListener listener : activeListeners.values()) {
            try {
                listener.shutdown();
            } catch (Exception e) {
                LOG.error("Error shutting down listener", e);
            }
        }
        activeListeners.clear();
    }
    
    /**
     * Force rebalance of slots for a specific topic.
     */
    public Mono<Void> rebalanceTopic(String topic, String groupId) {
        LOG.info("Forcing rebalance for topic {} group {}", topic, groupId);
        return slotManager.rebalanceSlots(topic, groupId);
    }
    
    /**
     * Get current slot assignments for this engine.
     */
    public Map<String, SlotAssignment> getCurrentAssignments() {
        return new HashMap<>(currentAssignments);
    }
    
    /**
     * Get slot manager health.
     */
    public Mono<KafkaSlotManager.SlotManagerHealth> getSlotHealth() {
        return slotManager.getHealth();
    }
}