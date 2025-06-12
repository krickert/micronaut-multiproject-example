package com.krickert.search.orchestrator.kafka.slot;

import com.krickert.search.orchestrator.kafka.listener.KafkaListenerManager;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.NonNull;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Integrates KafkaSlotManager with KafkaListenerManager to provide
 * slot-based partition assignment for Kafka consumers.
 * 
 * This class:
 * 1. Registers the engine instance with the slot manager
 * 2. Acquires slots when listeners are created
 * 3. Manages heartbeats to maintain slot ownership
 * 4. Watches for slot reassignments and adjusts listeners accordingly
 */
@Singleton
@Requires(property = "kafka.slot-manager.enabled", value = "true", defaultValue = "false")
public class SlotAwareKafkaListenerManager {
    private static final Logger LOG = LoggerFactory.getLogger(SlotAwareKafkaListenerManager.class);
    
    private final KafkaSlotManager slotManager;
    private final KafkaListenerManager listenerManager;
    private final String engineInstanceId;
    private final int maxSlots;
    private final Duration heartbeatInterval;
    
    // Track which slots are assigned to which listeners
    private final Map<String, List<KafkaSlot>> listenerSlots = new ConcurrentHashMap<>();
    private volatile boolean running = false;
    
    @Inject
    public SlotAwareKafkaListenerManager(
            KafkaSlotManager slotManager,
            KafkaListenerManager listenerManager,
            @Value("${engine.instance.id:${random.uuid}}") String engineInstanceId,
            @Value("${kafka.slot-manager.max-slots:10}") int maxSlots,
            @Value("${kafka.slot-manager.heartbeat-interval:PT30S}") Duration heartbeatInterval) {
        this.slotManager = slotManager;
        this.listenerManager = listenerManager;
        this.engineInstanceId = engineInstanceId;
        this.maxSlots = maxSlots;
        this.heartbeatInterval = heartbeatInterval;
        
        LOG.info("SlotAwareKafkaListenerManager initialized with engineId: {}, maxSlots: {}", 
                engineInstanceId, maxSlots);
    }
    
    @PostConstruct
    public void start() {
        LOG.info("Starting SlotAwareKafkaListenerManager for engine: {}", engineInstanceId);
        
        // Register this engine instance
        slotManager.registerEngine(engineInstanceId, maxSlots)
            .doOnSuccess(v -> LOG.info("Engine {} registered with slot manager", engineInstanceId))
            .doOnError(e -> LOG.error("Failed to register engine {}: {}", engineInstanceId, e.getMessage()))
            .subscribe();
        
        running = true;
        
        // Start heartbeat scheduler
        startHeartbeatScheduler();
        
        // Watch for slot assignment changes
        watchSlotAssignments();
    }
    
    @PreDestroy
    public void stop() {
        LOG.info("Stopping SlotAwareKafkaListenerManager for engine: {}", engineInstanceId);
        running = false;
        
        // Release all slots and unregister
        slotManager.unregisterEngine(engineInstanceId)
            .doOnSuccess(v -> LOG.info("Engine {} unregistered from slot manager", engineInstanceId))
            .doOnError(e -> LOG.error("Failed to unregister engine {}: {}", engineInstanceId, e.getMessage()))
            .block(Duration.ofSeconds(30));
    }
    
    /**
     * Acquire slots for a new listener being created.
     * This should be called when a listener is about to be created.
     */
    public Mono<List<KafkaSlot>> acquireSlots(String listenerKey, String topic, String groupId, int requestedSlots) {
        LOG.debug("Acquiring {} slots for listener {} on topic {} group {}", 
                requestedSlots, listenerKey, topic, groupId);
                
        return slotManager.acquireSlots(engineInstanceId, topic, groupId, requestedSlots)
            .map(SlotAssignment::slots)
            .doOnSuccess(slots -> {
                listenerSlots.put(listenerKey, slots);
                LOG.info("Acquired {} slots for listener {}: {}", 
                        slots.size(), listenerKey, 
                        slots.stream().map(KafkaSlot::partition).collect(Collectors.toList()));
            })
            .doOnError(e -> LOG.error("Failed to acquire slots for listener {}: {}", 
                    listenerKey, e.getMessage()));
    }
    
    /**
     * Release slots when a listener is being removed.
     */
    public Mono<Void> releaseSlots(String listenerKey) {
        List<KafkaSlot> slots = listenerSlots.remove(listenerKey);
        if (slots == null || slots.isEmpty()) {
            LOG.debug("No slots to release for listener {}", listenerKey);
            return Mono.empty();
        }
        
        LOG.info("Releasing {} slots for listener {}", slots.size(), listenerKey);
        return slotManager.releaseSlots(engineInstanceId, slots)
            .doOnSuccess(v -> LOG.info("Released slots for listener {}", listenerKey))
            .doOnError(e -> LOG.error("Failed to release slots for listener {}: {}", 
                    listenerKey, e.getMessage()));
    }
    
    /**
     * Get current slot assignments for this engine.
     */
    public Mono<SlotAssignment> getCurrentAssignments() {
        return slotManager.getAssignmentsForEngine(engineInstanceId);
    }
    
    /**
     * Start periodic heartbeat to maintain slot ownership.
     */
    private void startHeartbeatScheduler() {
        Flux.interval(heartbeatInterval)
            .filter(tick -> running && !listenerSlots.isEmpty())
            .flatMap(tick -> {
                List<KafkaSlot> allSlots = listenerSlots.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
                    
                if (allSlots.isEmpty()) {
                    return Mono.empty();
                }
                
                LOG.debug("Sending heartbeat for {} slots", allSlots.size());
                return slotManager.heartbeatSlots(engineInstanceId, allSlots)
                    .doOnError(e -> LOG.error("Heartbeat failed: {}", e.getMessage()));
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }
    
    /**
     * Watch for changes in slot assignments and react accordingly.
     */
    private void watchSlotAssignments() {
        slotManager.watchAssignments(engineInstanceId)
            .filter(assignment -> running)
            .subscribe(assignment -> {
                LOG.info("Received slot assignment change for engine {}: {} slots", 
                        engineInstanceId, assignment.slots().size());
                handleSlotReassignment(assignment);
            });
    }
    
    /**
     * Handle slot reassignment by adjusting listeners.
     * This might involve pausing/resuming listeners or adjusting their partition assignments.
     */
    private void handleSlotReassignment(SlotAssignment newAssignment) {
        // Group slots by topic and group
        Map<String, List<KafkaSlot>> slotsByTopicGroup = newAssignment.slots().stream()
            .collect(Collectors.groupingBy(slot -> slot.topic() + ":" + slot.groupId()));
        
        // Compare with current assignments and adjust
        Set<String> currentKeys = listenerSlots.keySet();
        Set<String> newKeys = slotsByTopicGroup.keySet();
        
        // Find removed assignments
        currentKeys.stream()
            .filter(key -> !newKeys.contains(key))
            .forEach(key -> {
                LOG.info("Slot assignment removed for {}, releasing listener", key);
                // TODO: Coordinate with listener manager to remove/pause listener
            });
        
        // Find new assignments
        newKeys.stream()
            .filter(key -> !currentKeys.contains(key))
            .forEach(key -> {
                LOG.info("New slot assignment for {}, may need to create listener", key);
                // TODO: Coordinate with listener manager to create/resume listener
            });
        
        // Update assignments
        listenerSlots.clear();
        slotsByTopicGroup.forEach(listenerSlots::put);
    }
    
    /**
     * Get slot distribution across all engines.
     */
    public Mono<Map<String, Integer>> getSlotDistribution() {
        return slotManager.getSlotDistribution();
    }
    
    /**
     * Get health status of the slot management system.
     */
    public Mono<KafkaSlotManager.SlotManagerHealth> getHealth() {
        return slotManager.getHealth();
    }
    
    /**
     * Force a rebalance of slots for a topic.
     */
    public Mono<Void> rebalanceSlots(@NonNull String topic, @NonNull String groupId) {
        LOG.info("Forcing slot rebalance for topic {} group {}", topic, groupId);
        return slotManager.rebalanceSlots(topic, groupId);
    }
}