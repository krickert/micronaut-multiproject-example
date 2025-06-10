package com.krickert.yappy.kafka.slot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.model.ConsulResponse;
import org.kiwiproject.consul.model.kv.ImmutableOperation;
import org.kiwiproject.consul.model.kv.Operation;
import org.kiwiproject.consul.model.kv.TxResponse;
import org.kiwiproject.consul.model.kv.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Consul-based implementation of KafkaSlotManager.
 * Uses Consul KV store to coordinate slot assignments across engine instances.
 */
@Singleton
@Requires(property = "consul.client.enabled", value = "true")
public class ConsulKafkaSlotManager implements KafkaSlotManager {
    
    private static final Logger LOG = LoggerFactory.getLogger(ConsulKafkaSlotManager.class);
    private static final String SLOT_PREFIX = "yappy/kafka-slots/";
    private static final String ENGINE_PREFIX = "yappy/kafka-engines/";
    private static final String ASSIGNMENT_PREFIX = "yappy/kafka-assignments/";
    
    private final Consul consul;
    private final ObjectMapper objectMapper;
    private final AdminClient kafkaAdmin;
    private final String engineInstanceId;
    private final long heartbeatTimeoutSeconds;
    private final Map<String, SlotAssignment> watchedAssignments = new ConcurrentHashMap<>();
    
    public ConsulKafkaSlotManager(
            Consul consul,
            ObjectMapper objectMapper,
            AdminClient kafkaAdmin,
            @Value("${app.kafka.slot.engine-instance-id:#{T(java.util.UUID).randomUUID().toString()}}") String engineInstanceId,
            @Value("${app.kafka.slot.heartbeat-timeout-seconds:30}") long heartbeatTimeoutSeconds) {
        this.consul = consul;
        this.objectMapper = objectMapper;
        this.kafkaAdmin = kafkaAdmin;
        this.engineInstanceId = engineInstanceId;
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }
    
    @PostConstruct
    void init() {
        LOG.info("Initializing ConsulKafkaSlotManager for engine instance: {}", engineInstanceId);
        // Register this engine on startup
        registerEngine(engineInstanceId, 10).subscribe();
    }
    
    @Override
    public Mono<Void> registerEngine(String engineInstanceId, int maxSlots) {
        // Input validation
        if (engineInstanceId == null || engineInstanceId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Engine instance ID cannot be null or blank"));
        }
        if (maxSlots <= 0) {
            return Mono.error(new IllegalArgumentException("Max slots must be greater than 0, but was: " + maxSlots));
        }
        
        return Mono.fromRunnable(() -> {
            try {
                String key = ENGINE_PREFIX + engineInstanceId;
                EngineRegistration registration = new EngineRegistration(
                        engineInstanceId, maxSlots, Instant.now(), "active"
                );
                String json = objectMapper.writeValueAsString(registration);
                
                KeyValueClient kvClient = consul.keyValueClient();
                kvClient.putValue(key, json);
                LOG.info("Registered engine {} with max slots: {}", engineInstanceId, maxSlots);
            } catch (Exception e) {
                LOG.error("Failed to register engine {}", engineInstanceId, e);
                throw new RuntimeException("Failed to register engine", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
    
    @Override
    public Mono<Void> unregisterEngine(String engineInstanceId) {
        return releaseAllSlotsForEngine(engineInstanceId)
                .then(Mono.<Void>fromRunnable(() -> {
                    String key = ENGINE_PREFIX + engineInstanceId;
                    consul.keyValueClient().deleteKey(key);
                    LOG.info("Unregistered engine {}", engineInstanceId);
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
    
    @Override
    public Mono<List<KafkaSlot>> getSlotsForTopic(String topic, String groupId) {
        return Mono.fromCallable(() -> {
            // First, get partition count from Kafka
            Map<String, TopicDescription> topics = kafkaAdmin.describeTopics(List.of(topic))
                    .allTopicNames().get();
            TopicDescription topicDesc = topics.get(topic);
            if (topicDesc == null) {
                LOG.warn("Topic {} not found in Kafka", topic);
                return List.<KafkaSlot>of();
            }
            
            int partitionCount = topicDesc.partitions().size();
            List<KafkaSlot> slots = new ArrayList<>();
            
            // Load existing slots from Consul
            String prefix = SLOT_PREFIX + topic + "/" + groupId + "/";
            KeyValueClient kvClient = consul.keyValueClient();
            List<org.kiwiproject.consul.model.kv.Value> values = kvClient.getValues(prefix);
            
            Map<Integer, KafkaSlot> existingSlots = new HashMap<>();
            if (values != null) {
                for (org.kiwiproject.consul.model.kv.Value value : values) {
                    // Skip lock keys
                    if (value.getKey().endsWith("-lock") || value.getKey().endsWith(".lock") || value.getKey().endsWith("global-lock")) {
                        continue;
                    }
                    
                    try {
                        KafkaSlot slot = objectMapper.readValue(
                                value.getValueAsString().orElse("{}"), 
                                KafkaSlot.class
                        );
                        existingSlots.put(slot.getPartition(), slot);
                    } catch (Exception e) {
                        LOG.error("Failed to deserialize slot from Consul", e);
                    }
                }
            }
            
            // Create or update slots for all partitions
            for (int partition = 0; partition < partitionCount; partition++) {
                if (existingSlots.containsKey(partition)) {
                    KafkaSlot slot = existingSlots.get(partition);
                    
                    // Check for expired heartbeats
                    if (slot.getStatus() == KafkaSlot.SlotStatus.ASSIGNED 
                            && slot.isHeartbeatExpired(heartbeatTimeoutSeconds)) {
                        slot.setStatus(KafkaSlot.SlotStatus.HEARTBEAT_EXPIRED);
                        LOG.warn("Slot {} has expired heartbeat", slot.getSlotId());
                    }
                    
                    slots.add(slot);
                } else {
                    // Create new slot in Consul to avoid race conditions
                    KafkaSlot newSlot = new KafkaSlot(topic, partition, groupId);
                    String key = prefix + partition;
                    try {
                        String json = objectMapper.writeValueAsString(newSlot);
                        kvClient.putValue(key, json);
                        LOG.debug("Pre-created slot {} in Consul", newSlot.getSlotId());
                    } catch (Exception e) {
                        LOG.error("Failed to pre-create slot {}", newSlot.getSlotId(), e);
                    }
                    slots.add(newSlot);
                }
            }
            
            return slots;
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<SlotAssignment> acquireSlots(String engineInstanceId, String topic, 
                                           String groupId, int requestedSlots) {
        // Input validation
        if (engineInstanceId == null || engineInstanceId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Engine instance ID cannot be null or blank"));
        }
        if (topic == null || topic.isBlank()) {
            return Mono.error(new IllegalArgumentException("Topic cannot be null or blank"));
        }
        if (groupId == null || groupId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Group ID cannot be null or blank"));
        }
        if (requestedSlots <= 0) {
            return Mono.error(new IllegalArgumentException("Requested slots must be greater than 0, but was: " + requestedSlots));
        }
        
        // First check engine's max slots
        return getEngineRegistration(engineInstanceId)
                .flatMap(engineReg -> {
                    if (engineReg.isEmpty()) {
                        return Mono.error(new IllegalStateException("Engine " + engineInstanceId + " is not registered"));
                    }
                    
                    EngineRegistration registration = engineReg.get();
                    
                    // Get current assignments to check against max slots
                    return getAssignmentsForEngine(engineInstanceId)
                            .flatMap(currentAssignment -> {
                                int currentSlotCount = currentAssignment.getSlotCount();
                                int maxSlots = registration.maxSlots();
                                int availableCapacity = maxSlots - currentSlotCount;
                                
                                if (availableCapacity <= 0) {
                                    LOG.warn("Engine {} already at max capacity ({} slots)", engineInstanceId, maxSlots);
                                    return Mono.just(currentAssignment);
                                }
                                
                                // Adjust requested slots to not exceed capacity
                                int slotsToAcquire = Math.min(requestedSlots, availableCapacity);
                                
                                return getSlotsForTopic(topic, groupId)
                                        .flatMap(slots -> {
                                            // Get ALL available slots, not just the requested amount
                                            List<KafkaSlot> availableSlots = slots.stream()
                                                    .filter(slot -> slot.getStatus() == KafkaSlot.SlotStatus.AVAILABLE 
                                                            || slot.getStatus() == KafkaSlot.SlotStatus.HEARTBEAT_EXPIRED)
                                                    .collect(Collectors.toList());
                                            
                                            if (availableSlots.isEmpty()) {
                                                LOG.info("No available slots for topic {} group {}", topic, groupId);
                                                return Mono.just(currentAssignment);
                                            }
                                            
                                            // Randomize the order to reduce contention
                                            Collections.shuffle(availableSlots);
                                            
                                            // Try to acquire up to slotsToAcquire from all available slots
                                            return acquireSlotsWithCAS(engineInstanceId, availableSlots, topic, groupId, slotsToAcquire)
                                                    .map(acquiredSlots -> {
                                                        if (acquiredSlots.isEmpty()) {
                                                            return currentAssignment;
                                                        }
                                                        
                                                        // Merge with existing assignments
                                                        List<KafkaSlot> allSlots = new ArrayList<>(currentAssignment.assignedSlots());
                                                        allSlots.addAll(acquiredSlots);
                                                        
                                                        SlotAssignment assignment = new SlotAssignment(
                                                                engineInstanceId, allSlots, 
                                                                currentAssignment.assignedAt(), 
                                                                Instant.now()
                                                        );
                                                        
                                                        // Save assignment
                                                        saveAssignment(engineInstanceId, assignment);
                                                        
                                                        LOG.info("Engine {} acquired {} slots for topic {} (total: {})", 
                                                                engineInstanceId, acquiredSlots.size(), topic, allSlots.size());
                                                        return assignment;
                                                    });
                                        });
                            });
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<Void> releaseSlots(String engineInstanceId, List<KafkaSlot> slots) {
        // Input validation
        if (engineInstanceId == null || engineInstanceId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Engine instance ID cannot be null or blank"));
        }
        if (slots == null || slots.isEmpty()) {
            return Mono.error(new IllegalArgumentException("Slots list cannot be null or empty"));
        }
        
        return Mono.fromRunnable(() -> {
            KeyValueClient kvClient = consul.keyValueClient();
            
            for (KafkaSlot slot : slots) {
                if (!engineInstanceId.equals(slot.getAssignedEngine())) {
                    LOG.warn("Engine {} trying to release slot {} owned by {}", 
                            engineInstanceId, slot.getSlotId(), slot.getAssignedEngine());
                    continue;
                }
                
                slot.release();
                String key = SLOT_PREFIX + slot.getTopic() + "/" + slot.getGroupId() + "/" + slot.getPartition();
                try {
                    String json = objectMapper.writeValueAsString(slot);
                    kvClient.putValue(key, json);
                } catch (Exception e) {
                    LOG.error("Failed to release slot {}", slot.getSlotId(), e);
                }
            }
            
            LOG.info("Engine {} released {} slots", engineInstanceId, slots.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
    
    @Override
    public Mono<Void> heartbeatSlots(String engineInstanceId, List<KafkaSlot> slots) {
        // Input validation
        if (engineInstanceId == null || engineInstanceId.isBlank()) {
            return Mono.error(new IllegalArgumentException("Engine instance ID cannot be null or blank"));
        }
        if (slots == null) {
            return Mono.error(new IllegalArgumentException("Slots list cannot be null"));
        }
        
        return Mono.fromRunnable(() -> {
            KeyValueClient kvClient = consul.keyValueClient();
            
            for (KafkaSlot slot : slots) {
                if (!engineInstanceId.equals(slot.getAssignedEngine())) {
                    continue;
                }
                
                slot.updateHeartbeat();
                String key = SLOT_PREFIX + slot.getTopic() + "/" + slot.getGroupId() + "/" + slot.getPartition();
                try {
                    String json = objectMapper.writeValueAsString(slot);
                    kvClient.putValue(key, json);
                } catch (Exception e) {
                    LOG.error("Failed to heartbeat slot {}", slot.getSlotId(), e);
                }
            }
            
            LOG.debug("Engine {} heartbeated {} slots", engineInstanceId, slots.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
    
    @Override
    public Mono<SlotAssignment> getAssignmentsForEngine(String engineInstanceId) {
        return Mono.fromCallable(() -> {
            String key = ASSIGNMENT_PREFIX + engineInstanceId;
            Optional<org.kiwiproject.consul.model.kv.Value> value = consul.keyValueClient().getValue(key);
            
            if (value.isPresent() && value.get().getValueAsString().isPresent()) {
                try {
                    return objectMapper.readValue(
                            value.get().getValueAsString().get(), 
                            SlotAssignment.class
                    );
                } catch (Exception e) {
                    LOG.error("Failed to read assignment for engine {}", engineInstanceId, e);
                }
            }
            
            return SlotAssignment.empty(engineInstanceId);
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Flux<SlotAssignment> watchAssignments(String engineInstanceId) {
        return Flux.interval(Duration.ofSeconds(5))
                .flatMap(tick -> getAssignmentsForEngine(engineInstanceId))
                .distinctUntilChanged()
                .doOnNext(assignment -> {
                    watchedAssignments.put(engineInstanceId, assignment);
                    LOG.debug("Assignment update for engine {}: {} slots", 
                            engineInstanceId, assignment.getSlotCount());
                });
    }
    
    @Override
    public Mono<Void> rebalanceSlots(String topic, String groupId) {
        return Mono.fromRunnable(() -> {
            LOG.info("Starting rebalance for topic {} group {}", topic, groupId);
            
            try {
                // Get all slots
                List<KafkaSlot> slots = getSlotsForTopic(topic, groupId).block();
                
                // Get all active engines
                List<EngineRegistration> engines = getActiveEngines();
                
                if (engines.isEmpty()) {
                    LOG.warn("No active engines available for rebalancing");
                    return;
                }
                
                // Simple round-robin assignment
                int engineIndex = 0;
                for (KafkaSlot slot : slots) {
                    EngineRegistration engine = engines.get(engineIndex % engines.size());
                    slot.assign(engine.engineInstanceId());
                    saveSlot(slot);
                    engineIndex++;
                }
                
                // Update assignments for each engine
                Map<String, List<KafkaSlot>> engineSlots = slots.stream()
                        .collect(Collectors.groupingBy(KafkaSlot::getAssignedEngine));
                
                for (Map.Entry<String, List<KafkaSlot>> entry : engineSlots.entrySet()) {
                    SlotAssignment assignment = new SlotAssignment(
                            entry.getKey(), entry.getValue(), Instant.now(), Instant.now()
                    );
                    saveAssignment(entry.getKey(), assignment);
                }
                
                LOG.info("Rebalance complete for topic {} group {}", topic, groupId);
            } catch (Exception e) {
                LOG.error("Failed to rebalance slots", e);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }
    
    @Override
    public Mono<SlotManagerHealth> getHealth() {
        return Mono.fromCallable(() -> {
            try {
                List<org.kiwiproject.consul.model.kv.Value> allSlots = consul.keyValueClient().getValues(SLOT_PREFIX);
                int totalSlots = allSlots != null ? allSlots.size() : 0;
                
                long assignedSlots = allSlots != null ? allSlots.stream()
                        .map(v -> {
                            try {
                                KafkaSlot slot = objectMapper.readValue(
                                        v.getValueAsString().orElse("{}"), 
                                        KafkaSlot.class
                                );
                                return slot.getStatus() == KafkaSlot.SlotStatus.ASSIGNED;
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .filter(assigned -> assigned)
                        .count() : 0;
                
                List<EngineRegistration> engines = getActiveEngines();
                
                return new SlotManagerHealth(
                        true,
                        totalSlots,
                        (int) assignedSlots,
                        (int) (totalSlots - assignedSlots),
                        engines.size(),
                        null
                );
            } catch (Exception e) {
                return new SlotManagerHealth(false, 0, 0, 0, 0, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    /**
     * Scheduled heartbeat for this engine's slots.
     */
    @Scheduled(fixedDelay = "${app.kafka.slot.heartbeat-interval-seconds:10}s")
    void heartbeatOwnSlots() {
        getAssignmentsForEngine(engineInstanceId)
                .flatMap(assignment -> heartbeatSlots(engineInstanceId, assignment.assignedSlots()))
                .subscribe(
                        v -> LOG.debug("Heartbeat complete for engine {}", engineInstanceId),
                        error -> LOG.error("Failed to heartbeat slots", error)
                );
    }
    
    /**
     * Scheduled cleanup of expired slots.
     */
    @Scheduled(fixedDelay = "${app.kafka.slot.cleanup-interval-seconds:60}s")
    void cleanupExpiredSlots() {
        LOG.info("Running expired slot cleanup at {}", Instant.now());
        
        try {
            // Get all slots from Consul
            KeyValueClient kvClient = consul.keyValueClient();
            List<org.kiwiproject.consul.model.kv.Value> allSlotValues = kvClient.getValues(SLOT_PREFIX);
            
            if (allSlotValues != null && !allSlotValues.isEmpty()) {
                LOG.info("Found {} slot values in Consul under prefix: {}", allSlotValues.size(), SLOT_PREFIX);
                for (org.kiwiproject.consul.model.kv.Value value : allSlotValues) {
                    // Skip lock keys
                    if (value.getKey().endsWith("-lock") || value.getKey().endsWith(".lock") || value.getKey().endsWith("global-lock")) {
                        continue;
                    }
                    
                    try {
                        String slotJson = value.getValueAsString().orElse("{}");
                        KafkaSlot slot = objectMapper.readValue(slotJson, KafkaSlot.class);
                        
                        boolean isExpired = slot.isHeartbeatExpired(heartbeatTimeoutSeconds);
                        LOG.info("Checking slot {} - status: {}, assigned to: {}, last heartbeat: {}, expired: {}, timeout: {}s", 
                                value.getKey(), slot.getStatus(), slot.getAssignedEngine(), slot.getLastHeartbeat(), 
                                isExpired, heartbeatTimeoutSeconds);
                        
                        // Check if slot has expired heartbeat
                        if (slot.getStatus() == KafkaSlot.SlotStatus.ASSIGNED && isExpired) {
                            
                            // Update slot status to HEARTBEAT_EXPIRED
                            slot.setStatus(KafkaSlot.SlotStatus.HEARTBEAT_EXPIRED);
                            String updatedJson = objectMapper.writeValueAsString(slot);
                            
                            // Save updated slot back to Consul
                            kvClient.putValue(value.getKey(), updatedJson);
                            LOG.info("Marked slot {} as HEARTBEAT_EXPIRED (assigned to: {}, last heartbeat: {})", 
                                    slot.getSlotId(), slot.getAssignedEngine(), slot.getLastHeartbeat());
                        } else if (slot.getStatus() == KafkaSlot.SlotStatus.ASSIGNED) {
                            LOG.trace("Slot {} still active (assigned to: {}, last heartbeat: {}, expired: {})", 
                                    slot.getSlotId(), slot.getAssignedEngine(), slot.getLastHeartbeat(),
                                    slot.isHeartbeatExpired(heartbeatTimeoutSeconds));
                        }
                    } catch (Exception e) {
                        LOG.error("Error processing slot during cleanup", e);
                    }
                }
            } else {
                LOG.info("No slot values found in Consul under prefix: {}", SLOT_PREFIX);
            }
        } catch (Exception e) {
            LOG.error("Error during expired slot cleanup", e);
        }
    }
    
    private void saveSlot(KafkaSlot slot) {
        String key = SLOT_PREFIX + slot.getTopic() + "/" + slot.getGroupId() + "/" + slot.getPartition();
        try {
            String json = objectMapper.writeValueAsString(slot);
            consul.keyValueClient().putValue(key, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to save slot {}", slot.getSlotId(), e);
        }
    }
    
    private void saveAssignment(String engineInstanceId, SlotAssignment assignment) {
        String key = ASSIGNMENT_PREFIX + engineInstanceId;
        try {
            String json = objectMapper.writeValueAsString(assignment);
            consul.keyValueClient().putValue(key, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to save assignment for engine {}", engineInstanceId, e);
        }
    }
    
    private List<EngineRegistration> getActiveEngines() {
        List<org.kiwiproject.consul.model.kv.Value> values = consul.keyValueClient().getValues(ENGINE_PREFIX);
        List<EngineRegistration> engines = new ArrayList<>();
        
        if (values != null) {
            for (org.kiwiproject.consul.model.kv.Value value : values) {
                try {
                    EngineRegistration engine = objectMapper.readValue(
                            value.getValueAsString().orElse("{}"), 
                            EngineRegistration.class
                    );
                    if ("active".equals(engine.status())) {
                        engines.add(engine);
                    }
                } catch (Exception e) {
                    LOG.error("Failed to deserialize engine registration", e);
                }
            }
        }
        
        return engines;
    }
    
    private Mono<Optional<EngineRegistration>> getEngineRegistration(String engineInstanceId) {
        return Mono.fromCallable(() -> {
            String key = ENGINE_PREFIX + engineInstanceId;
            Optional<org.kiwiproject.consul.model.kv.Value> value = consul.keyValueClient().getValue(key);
            
            if (value.isPresent() && value.get().getValueAsString().isPresent()) {
                try {
                    EngineRegistration registration = objectMapper.readValue(
                            value.get().getValueAsString().get(), 
                            EngineRegistration.class
                    );
                    return Optional.of(registration);
                } catch (Exception e) {
                    LOG.error("Failed to read engine registration for {}", engineInstanceId, e);
                }
            }
            
            return Optional.<EngineRegistration>empty();
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<List<KafkaSlot>> acquireSlotsWithCAS(String engineInstanceId, List<KafkaSlot> availableSlots, 
                                                      String topic, String groupId, int maxSlotsToAcquire) {
        return Mono.fromCallable(() -> {
            List<KafkaSlot> acquiredSlots = new ArrayList<>();
            KeyValueClient kvClient = consul.keyValueClient();
            
            for (KafkaSlot slot : availableSlots) {
                // Stop if we've acquired enough slots
                if (acquiredSlots.size() >= maxSlotsToAcquire) {
                    break;
                }
                String key = SLOT_PREFIX + topic + "/" + groupId + "/" + slot.getPartition();
                
                try {
                    // Read current slot state with consistent read
                    Optional<org.kiwiproject.consul.model.kv.Value> currentValue = kvClient.getValue(key);
                    
                    KafkaSlot currentSlot;
                    long modifyIndex = 0L;
                    
                    if (currentValue.isPresent()) {
                        String valueStr = currentValue.get().getValueAsString().orElse("{}");
                        currentSlot = objectMapper.readValue(valueStr, KafkaSlot.class);
                        modifyIndex = currentValue.get().getModifyIndex();
                        
                        // Check if slot is still available
                        if (currentSlot.getStatus() != KafkaSlot.SlotStatus.AVAILABLE && 
                            currentSlot.getStatus() != KafkaSlot.SlotStatus.HEARTBEAT_EXPIRED) {
                            continue;
                        }
                    } else {
                        // New slot
                        currentSlot = slot;
                    }
                    
                    // Update slot with new assignment
                    currentSlot.assign(engineInstanceId);
                    String newJson = objectMapper.writeValueAsString(currentSlot);
                    
                    // Since slots are pre-created, we should always have a modifyIndex > 0
                    // If not, something is wrong
                    if (modifyIndex == 0) {
                        LOG.warn("Slot {} not found in Consul - this shouldn't happen", slot.getSlotId());
                        continue;
                    }
                    
                    // Use CAS transaction for atomic update
                    List<Operation> operations = List.of(
                        ImmutableOperation.builder()
                            .verb(Verb.CHECK_INDEX.toValue())
                            .key(key)
                            .index(java.math.BigInteger.valueOf(modifyIndex))
                            .build(),
                        ImmutableOperation.builder()
                            .verb(Verb.SET.toValue())
                            .key(key)
                            .value(newJson)
                            .build()
                    );
                    
                    // Execute transaction
                    ConsulResponse<TxResponse> response = kvClient.performTransaction(
                            operations.toArray(new Operation[0]));
                    
                    if (response != null && response.getResponse() != null) {
                        TxResponse txResponse = response.getResponse();
                        boolean success = txResponse.errors() == null || txResponse.errors().isEmpty();
                        
                        if (success) {
                            acquiredSlots.add(currentSlot);
                            LOG.debug("Successfully acquired slot {} using CAS", slot.getSlotId());
                        } else {
                            LOG.debug("CAS failed for slot {} - another engine got it", slot.getSlotId());
                        }
                    }
                    
                } catch (Exception e) {
                    LOG.debug("Error acquiring slot {}: {}", slot.getSlotId(), e.getMessage());
                }
            }
            
            return acquiredSlots;
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<Void> releaseAllSlotsForEngine(String engineInstanceId) {
        return getAssignmentsForEngine(engineInstanceId)
                .flatMap(assignment -> releaseSlots(engineInstanceId, assignment.assignedSlots()));
    }
    
    record EngineRegistration(
            String engineInstanceId,
            int maxSlots,
            Instant registeredAt,
            String status
    ) {}
    
    /**
     * Clean up all slot management data from Consul.
     * This is mainly for testing purposes.
     */
    public Mono<Void> cleanup() {
        return Mono.fromRunnable(() -> {
            LOG.warn("Cleaning up all slot management data from Consul");
            try {
                // Delete all keys under our prefix
                // Delete all keys under our prefixes
                consul.keyValueClient().deleteKeys(SLOT_PREFIX);
                consul.keyValueClient().deleteKeys(ENGINE_PREFIX);
                consul.keyValueClient().deleteKeys(ASSIGNMENT_PREFIX);
                LOG.info("Cleanup completed");
            } catch (Exception e) {
                LOG.error("Failed to cleanup Consul data", e);
                throw new RuntimeException("Cleanup failed", e);
            }
        });
    }
    
    @Override
    public Flux<EngineInfo> getRegisteredEngines() {
        return Flux.defer(() -> {
            try {
                KeyValueClient kvClient = consul.keyValueClient();
                List<String> engineKeys = kvClient.getKeys(ENGINE_PREFIX);
                
                return Flux.fromIterable(engineKeys)
                        .filter(key -> !key.endsWith("/"))
                        .flatMap(key -> {
                            String engineId = key.substring(ENGINE_PREFIX.length());
                            return getEngineInfo(engineId);
                        });
            } catch (Exception e) {
                LOG.error("Failed to get registered engines", e);
                return Flux.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<Map<String, List<KafkaSlot>>> getAllSlots() {
        return Mono.defer(() -> {
            try {
                KeyValueClient kvClient = consul.keyValueClient();
                List<String> slotKeys = kvClient.getKeys(SLOT_PREFIX);
                
                Map<String, List<KafkaSlot>> allSlots = new HashMap<>();
                
                for (String key : slotKeys) {
                    if (!key.endsWith("/")) {
                        Optional<String> value = kvClient.getValueAsString(key);
                        if (value.isPresent()) {
                            KafkaSlot slot = objectMapper.readValue(value.get(), KafkaSlot.class);
                            String topicGroup = slot.getTopic() + ":" + slot.getGroupId();
                            allSlots.computeIfAbsent(topicGroup, k -> new ArrayList<>()).add(slot);
                        }
                    }
                }
                
                return Mono.just(allSlots);
            } catch (Exception e) {
                LOG.error("Failed to get all slots", e);
                return Mono.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<Map<String, Integer>> getSlotDistribution() {
        return Mono.defer(() -> {
            try {
                Map<String, Integer> distribution = new HashMap<>();
                
                // Count slots per engine from assignments
                KeyValueClient kvClient = consul.keyValueClient();
                List<String> assignmentKeys = kvClient.getKeys(ASSIGNMENT_PREFIX);
                
                for (String key : assignmentKeys) {
                    if (!key.endsWith("/")) {
                        String engineId = key.substring(ASSIGNMENT_PREFIX.length());
                        Optional<String> value = kvClient.getValueAsString(key);
                        if (value.isPresent()) {
                            SlotAssignment assignment = objectMapper.readValue(value.get(), SlotAssignment.class);
                            distribution.put(engineId, assignment.assignedSlots().size());
                        }
                    }
                }
                
                return Mono.just(distribution);
            } catch (Exception e) {
                LOG.error("Failed to get slot distribution", e);
                return Mono.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }
    
    private Mono<EngineInfo> getEngineInfo(String engineId) {
        return Mono.defer(() -> {
            try {
                KeyValueClient kvClient = consul.keyValueClient();
                String engineKey = ENGINE_PREFIX + engineId;
                Optional<String> engineData = kvClient.getValueAsString(engineKey);
                
                if (engineData.isPresent()) {
                    EngineRegistration registration = objectMapper.readValue(engineData.get(), EngineRegistration.class);
                    
                    // Get current slot count
                    String assignmentKey = ASSIGNMENT_PREFIX + engineId;
                    Optional<String> assignmentData = kvClient.getValueAsString(assignmentKey);
                    int currentSlots = 0;
                    if (assignmentData.isPresent()) {
                        SlotAssignment assignment = objectMapper.readValue(assignmentData.get(), SlotAssignment.class);
                        currentSlots = assignment.assignedSlots().size();
                    }
                    
                    // For heartbeat, we need to check the last assignment update time
                    // since EngineRegistration only has registeredAt
                    Instant lastHeartbeat = registration.registeredAt();
                    if (assignmentData.isPresent()) {
                        SlotAssignment assignment = objectMapper.readValue(assignmentData.get(), SlotAssignment.class);
                        if (assignment.lastUpdated() != null) {
                            lastHeartbeat = assignment.lastUpdated();
                        }
                    }
                    
                    boolean isActive = Duration.between(lastHeartbeat, Instant.now()).getSeconds() < heartbeatTimeoutSeconds;
                    
                    return Mono.just(new EngineInfo(
                            engineId,
                            registration.maxSlots(),
                            currentSlots,
                            lastHeartbeat,
                            isActive
                    ));
                }
                
                return Mono.empty();
            } catch (Exception e) {
                LOG.error("Failed to get engine info for {}", engineId, e);
                return Mono.error(e);
            }
        });
    }
}