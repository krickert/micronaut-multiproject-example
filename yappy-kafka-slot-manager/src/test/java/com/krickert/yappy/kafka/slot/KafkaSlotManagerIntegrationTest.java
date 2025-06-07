package com.krickert.yappy.kafka.slot;

import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ConsulKafkaSlotManager.
 * Tests the full slot management functionality with real Consul and Kafka instances.
 */
@MicronautTest(environments = {"test"})
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "app.kafka.slot.heartbeat-timeout-seconds", value = "3")
@Property(name = "app.kafka.slot.cleanup-interval-seconds", value = "2")
@Property(name = "app.kafka.slot.heartbeat-interval-seconds", value = "1")
public class KafkaSlotManagerIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSlotManagerIntegrationTest.class);
    
    private static final String TEST_TOPIC = "test-slot-topic";
    private static final String TEST_GROUP = "test-slot-group";
    private static final int PARTITIONS = 10;
    
    @Inject
    KafkaSlotManager slotManager;
    
    @Inject
    AdminClient kafkaAdminClient;
    
    @BeforeEach
    void setUp() throws Exception {
        LOG.info("=== Setting up Kafka slot manager test ===");
        
        // Clean up any existing state in Consul
        if (slotManager instanceof ConsulKafkaSlotManager consulSlotManager) {
            consulSlotManager.cleanup().block(Duration.ofSeconds(5));
        }
        
        // Create test topic if it doesn't exist
        try {
            var newTopic = new NewTopic(TEST_TOPIC, PARTITIONS, (short) 1);
            kafkaAdminClient.createTopics(Collections.singletonList(newTopic)).all().get(10, TimeUnit.SECONDS);
            LOG.info("Created test topic {} with {} partitions", TEST_TOPIC, PARTITIONS);
        } catch (Exception e) {
            if (e.getCause() instanceof org.apache.kafka.common.errors.TopicExistsException) {
                LOG.info("Test topic {} already exists", TEST_TOPIC);
            } else {
                throw e;
            }
        }
        
        // Give Kafka time to propagate topic creation
        Thread.sleep(1000);
    }
    
    @AfterEach
    void tearDown() throws Exception {
        LOG.info("=== Cleaning up Kafka slot manager test ===");
        
        // Clean up Consul state
        if (slotManager instanceof ConsulKafkaSlotManager consulSlotManager) {
            consulSlotManager.cleanup().block(Duration.ofSeconds(5));
        }
        
        // Delete test topic if it exists
        try {
            kafkaAdminClient.deleteTopics(Collections.singletonList(TEST_TOPIC)).all().get(10, TimeUnit.SECONDS);
            LOG.info("Deleted test topic {}", TEST_TOPIC);
        } catch (Exception e) {
            LOG.info("Test topic {} deletion failed or topic doesn't exist: {}", TEST_TOPIC, e.getMessage());
        }
    }
    
    @Test
    @DisplayName("Should register engine and verify health")
    void testEngineRegistration() {
        String engineId = "test-engine-1";
        int maxSlots = 50;
        
        // Register engine
        StepVerifier.create(slotManager.registerEngine(engineId, maxSlots))
                .verifyComplete();
        
        LOG.info("Engine {} registered with max slots {}", engineId, maxSlots);
        
        // Verify health
        StepVerifier.create(slotManager.getHealth())
                .assertNext(health -> {
                    assertTrue(health.healthy());
                    assertEquals(1, health.registeredEngines());
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should acquire slots for single engine")
    void testSingleEngineSlotAcquisition() {
        String engineId = "test-engine-2";
        
        // Register engine
        StepVerifier.create(slotManager.registerEngine(engineId, 100))
                .verifyComplete();
        
        // Acquire slots
        StepVerifier.create(slotManager.acquireSlots(engineId, TEST_TOPIC, TEST_GROUP, 5))
                .assertNext(assignment -> {
                    assertNotNull(assignment);
                    assertEquals(5, assignment.getSlotCount());
                    assertEquals(engineId, assignment.engineInstanceId());
                    
                    // Verify partition assignment
                    var partitions = assignment.assignedSlots().stream()
                            .map(KafkaSlot::getPartition)
                            .collect(Collectors.toSet());
                    assertEquals(5, partitions.size());
                    
                    LOG.info("Engine {} acquired partitions: {}", engineId, partitions);
                })
                .verifyComplete();
        
        // Verify health shows active slots
        StepVerifier.create(slotManager.getHealth())
                .assertNext(health -> {
                    assertEquals(5, health.assignedSlots());
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should distribute slots across multiple engines")
    void testMultiEngineSlotDistribution() {
        String engine1 = "test-engine-3a";
        String engine2 = "test-engine-3b";
        
        // Register both engines
        Mono<Void> registerBoth = Flux.merge(
                slotManager.registerEngine(engine1, 100),
                slotManager.registerEngine(engine2, 100)
        ).then();
        
        StepVerifier.create(registerBoth)
                .verifyComplete();
        
        // Both engines acquire slots
        AtomicReference<SlotAssignment> assignment1 = new AtomicReference<>();
        AtomicReference<SlotAssignment> assignment2 = new AtomicReference<>();
        
        Mono<Void> acquireBoth = Flux.merge(
                slotManager.acquireSlots(engine1, TEST_TOPIC, TEST_GROUP, 5)
                        .doOnNext(assignment1::set),
                slotManager.acquireSlots(engine2, TEST_TOPIC, TEST_GROUP, 5)
                        .doOnNext(assignment2::set)
        ).then();
        
        StepVerifier.create(acquireBoth)
                .verifyComplete();
        
        // Verify no partition overlap
        Set<Integer> partitions1 = assignment1.get().assignedSlots().stream()
                .map(KafkaSlot::getPartition)
                .collect(Collectors.toSet());
        Set<Integer> partitions2 = assignment2.get().assignedSlots().stream()
                .map(KafkaSlot::getPartition)
                .collect(Collectors.toSet());
        
        assertTrue(Collections.disjoint(partitions1, partitions2), 
                "Engines should not have overlapping partitions");
        assertEquals(10, partitions1.size() + partitions2.size(), 
                "All partitions should be assigned");
        
        LOG.info("Engine {} got partitions: {}", engine1, partitions1);
        LOG.info("Engine {} got partitions: {}", engine2, partitions2);
    }
    
    @Test
    @DisplayName("Should handle slot heartbeats and expiration")
    void testSlotHeartbeatAndExpiration() throws Exception {
        String engineId = "test-engine-4";
        
        // Register engine and acquire slots
        StepVerifier.create(slotManager.registerEngine(engineId, 100))
                .verifyComplete();
        
        AtomicReference<List<KafkaSlot>> acquiredSlots = new AtomicReference<>();
        StepVerifier.create(slotManager.acquireSlots(engineId, TEST_TOPIC, TEST_GROUP, 3))
                .assertNext(assignment -> {
                    assertEquals(3, assignment.getSlotCount());
                    acquiredSlots.set(assignment.assignedSlots());
                })
                .verifyComplete();
        
        // Send heartbeats for 2 seconds
        CountDownLatch heartbeatLatch = new CountDownLatch(2);
        Thread heartbeatThread = new Thread(() -> {
            for (int i = 0; i < 2; i++) {
                try {
                    Thread.sleep(1000);
                    slotManager.heartbeatSlots(engineId, acquiredSlots.get()).block();
                    LOG.info("Sent heartbeat {}", i + 1);
                    heartbeatLatch.countDown();
                } catch (Exception e) {
                    LOG.error("Heartbeat failed", e);
                }
            }
        });
        heartbeatThread.start();
        
        // Wait for heartbeats
        assertTrue(heartbeatLatch.await(5, TimeUnit.SECONDS));
        
        // Verify slots are still held
        StepVerifier.create(slotManager.getHealth())
                .assertNext(health -> assertEquals(3, health.assignedSlots()))
                .verifyComplete();
        
        // Stop heartbeats and wait for expiration
        heartbeatThread.join();
        LOG.info("Stopped heartbeats, waiting for slot expiration...");
        
        // Manually trigger cleanup after heartbeat timeout to test the logic
        Thread.sleep(3500); // Wait for heartbeat timeout (3s) + buffer
        if (slotManager instanceof ConsulKafkaSlotManager consulSlotManager) {
            LOG.info("Manually triggering cleanup");
            consulSlotManager.cleanupExpiredSlots();
        }
        
        // Wait for slots to expire (3 seconds + buffer)
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))
                .until(() -> {
                    var health = slotManager.getHealth().block();
                    LOG.info("Active slots: {}, Total slots: {}", health.assignedSlots(), health.totalSlots());
                    
                    // Also check slots directly in Consul
                    var slotList = slotManager.getSlotsForTopic(TEST_TOPIC, TEST_GROUP).block();
                    for (var slot : slotList) {
                        if (slot.getStatus() == KafkaSlot.SlotStatus.ASSIGNED) {
                            LOG.info("Slot {} still assigned to {} (last heartbeat: {})", 
                                    slot.getSlotId(), slot.getAssignedEngine(), slot.getLastHeartbeat());
                        }
                    }
                    
                    return health.assignedSlots() == 0;
                });
        
        LOG.info("Slots expired successfully");
    }
    
    @Test
    @DisplayName("Should release slots explicitly")
    void testSlotRelease() {
        String engineId = "test-engine-5";
        
        // Register and acquire slots
        StepVerifier.create(slotManager.registerEngine(engineId, 100))
                .verifyComplete();
        
        AtomicReference<List<KafkaSlot>> acquiredSlots = new AtomicReference<>();
        StepVerifier.create(slotManager.acquireSlots(engineId, TEST_TOPIC, TEST_GROUP, 4))
                .assertNext(assignment -> {
                    assertEquals(4, assignment.getSlotCount());
                    acquiredSlots.set(assignment.assignedSlots());
                })
                .verifyComplete();
        
        // Release 2 slots
        List<KafkaSlot> slotsToRelease = acquiredSlots.get().subList(0, 2);
        StepVerifier.create(slotManager.releaseSlots(engineId, slotsToRelease))
                .verifyComplete();
        
        LOG.info("Released {} slots", slotsToRelease.size());
        
        // Verify remaining slots
        StepVerifier.create(slotManager.getHealth())
                .assertNext(health -> assertEquals(2, health.assignedSlots()))
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle concurrent slot requests")
    void testConcurrentSlotRequests() throws Exception {
        int engineCount = 5;
        List<String> engineIds = new ArrayList<>();
        
        // Register multiple engines
        for (int i = 0; i < engineCount; i++) {
            String engineId = "concurrent-engine-" + i;
            engineIds.add(engineId);
            slotManager.registerEngine(engineId, 100).block();
        }
        
        // All engines request slots concurrently
        CountDownLatch latch = new CountDownLatch(engineCount);
        Map<String, Set<Integer>> enginePartitions = new HashMap<>();
        
        for (String engineId : engineIds) {
            new Thread(() -> {
                try {
                    SlotAssignment assignment = slotManager.acquireSlots(
                            engineId, TEST_TOPIC, TEST_GROUP, 2).block();
                    
                    Set<Integer> partitions = assignment.assignedSlots().stream()
                            .map(KafkaSlot::getPartition)
                            .collect(Collectors.toSet());
                    
                    synchronized (enginePartitions) {
                        enginePartitions.put(engineId, partitions);
                    }
                    
                    LOG.info("Engine {} acquired partitions: {}", engineId, partitions);
                } catch (Exception e) {
                    LOG.error("Failed to acquire slots for engine {}", engineId, e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }
        
        // Wait for all acquisitions
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        // Verify no partition conflicts
        Set<Integer> allAssignedPartitions = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> entry : enginePartitions.entrySet()) {
            for (Integer partition : entry.getValue()) {
                assertTrue(allAssignedPartitions.add(partition), 
                        "Partition " + partition + " assigned to multiple engines!");
            }
        }
        
        // All partitions should be assigned
        assertEquals(PARTITIONS, allAssignedPartitions.size(), 
                "All partitions should be assigned");
        
        LOG.info("Concurrent test successful: {} partitions distributed across {} engines",
                allAssignedPartitions.size(), engineCount);
    }
    
    @Test
    @DisplayName("Should prevent over-allocation beyond max slots")
    void testMaxSlotsEnforcement() {
        String engineId = "test-engine-max-slots";
        int maxSlots = 3;
        
        // Register engine with limited slots
        StepVerifier.create(slotManager.registerEngine(engineId, maxSlots))
                .verifyComplete();
        
        // Try to acquire more than max slots
        StepVerifier.create(slotManager.acquireSlots(engineId, TEST_TOPIC, TEST_GROUP, 5))
                .assertNext(assignment -> {
                    // Should only get maxSlots number of slots
                    assertTrue(assignment.getSlotCount() <= maxSlots,
                            "Should not exceed max slots");
                    LOG.info("Engine requested 5 slots but got {} (max: {})", 
                            assignment.getSlotCount(), maxSlots);
                })
                .verifyComplete();
    }
    
    @Test
    @DisplayName("Should handle slot rebalancing after engine failure")
    void testSlotRebalancingAfterFailure() throws Exception {
        String engine1 = "test-engine-rebalance-1";
        String engine2 = "test-engine-rebalance-2";
        
        // Register both engines
        slotManager.registerEngine(engine1, 100).block();
        slotManager.registerEngine(engine2, 100).block();
        
        // Engine 1 acquires all slots
        SlotAssignment assignment1 = slotManager.acquireSlots(
                engine1, TEST_TOPIC, TEST_GROUP, PARTITIONS).block();
        assertEquals(PARTITIONS, assignment1.getSlotCount());
        
        LOG.info("Engine 1 acquired all {} partitions", PARTITIONS);
        
        // Simulate engine 1 failure (stop heartbeats and wait for expiration)
        // Heartbeat timeout is 3 seconds, cleanup runs every 2 seconds
        Thread.sleep(5000);  // Wait for heartbeat timeout + cleanup cycle
        
        // Engine 2 should now be able to acquire the expired slots
        SlotAssignment assignment2 = slotManager.acquireSlots(
                engine2, TEST_TOPIC, TEST_GROUP, PARTITIONS).block();
        
        assertNotNull(assignment2);
        assertTrue(assignment2.getSlotCount() > 0, 
                "Engine 2 should acquire some slots after engine 1 failure");
        
        LOG.info("After engine 1 failure, engine 2 acquired {} partitions", 
                assignment2.getSlotCount());
    }
}