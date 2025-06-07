package com.krickert.search.pipeline.engine.kafka.slot;

import com.krickert.search.config.consul.DynamicConfigurationManager;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.pipeline.engine.kafka.admin.PipelineKafkaTopicService;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Full integration test for Kafka slot management with Consul.
 * Tests coordination between multiple engine instances competing for slots.
 */
@MicronautTest(environments = {"test", "kafka-slot-test"})
@Property(name = "consul.client.enabled", value = "true")
@Property(name = "kafka.enabled", value = "true")
@Property(name = "app.kafka.slot-management.enabled", value = "true")
@Property(name = "app.kafka.slot.heartbeat-timeout-seconds", value = "10")
@Property(name = "app.kafka.slot.heartbeat-interval-seconds", value = "3")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class KafkaSlotManagementIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(KafkaSlotManagementIntegrationTest.class);
    private static final String TEST_TOPIC = "test-slot-topic";
    private static final String TEST_GROUP = "test-slot-group";
    private static final String TEST_PIPELINE = "test-pipeline";
    private static final String TEST_STEP = "test-step";
    
    @Inject
    com.krickert.yappy.kafka.slot.ConsulKafkaSlotManager slotManager;
    
    @Inject
    PipelineKafkaTopicService kafkaTopicService;
    
    @Inject
    AdminClient kafkaAdmin;
    
    @Inject
    DynamicConfigurationManager configManager;
    
    private String engine1Id;
    private String engine2Id;
    
    @BeforeEach
    void setUp() {
        engine1Id = "test-engine-1-" + UUID.randomUUID();
        engine2Id = "test-engine-2-" + UUID.randomUUID();
        
        // Create test topic with multiple partitions
        kafkaTopicService.createAllTopics(TEST_PIPELINE, TEST_STEP);
        
        // Wait for topic creation
        await().atMost(Duration.ofSeconds(10))
                .until(() -> {
                    try {
                        var topics = kafkaAdmin.listTopics().names().get();
                        return topics.stream().anyMatch(t -> t.contains(TEST_PIPELINE));
                    } catch (Exception e) {
                        return false;
                    }
                });
    }
    
    @Test
    @Order(1)
    @DisplayName("Should register and unregister engines")
    void testEngineRegistration() {
        // Register engines
        slotManager.registerEngine(engine1Id, 5).block();
        slotManager.registerEngine(engine2Id, 5).block();
        
        // Check health shows registered engines
        var health = slotManager.getHealth().block();
        assertNotNull(health);
        assertTrue(health.healthy());
        assertEquals(2, health.registeredEngines());
        
        // Unregister one engine
        slotManager.unregisterEngine(engine2Id).block();
        
        // Check health again
        health = slotManager.getHealth().block();
        assertEquals(1, health.registeredEngines());
    }
    
    @Test
    @Order(2)
    @DisplayName("Should acquire and release slots")
    void testSlotAcquisition() throws Exception {
        // Register engine
        slotManager.registerEngine(engine1Id, 10).block();
        
        // Get actual topic name from kafka topic service
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        // Acquire slots
        SlotAssignment assignment = slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 3).block();
        
        assertNotNull(assignment);
        assertEquals(engine1Id, assignment.engineInstanceId());
        assertEquals(3, assignment.getSlotCount());
        
        // Verify slots are marked as assigned
        List<KafkaSlot> slots = slotManager.getSlotsForTopic(actualTopic, TEST_GROUP).block();
        long assignedCount = slots.stream()
                .filter(s -> s.getStatus() == KafkaSlot.SlotStatus.ASSIGNED)
                .count();
        assertEquals(3, assignedCount);
        
        // Release slots
        slotManager.releaseSlots(engine1Id, assignment.assignedSlots()).block();
        
        // Verify slots are available again
        slots = slotManager.getSlotsForTopic(actualTopic, TEST_GROUP).block();
        assignedCount = slots.stream()
                .filter(s -> s.getStatus() == KafkaSlot.SlotStatus.ASSIGNED)
                .count();
        assertEquals(0, assignedCount);
    }
    
    @Test
    @Order(3)
    @DisplayName("Should handle multiple engines competing for slots")
    void testMultiEngineCompetition() throws Exception {
        // Register both engines
        slotManager.registerEngine(engine1Id, 10).block();
        slotManager.registerEngine(engine2Id, 10).block();
        
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        // Both engines try to acquire slots concurrently
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger engine1Slots = new AtomicInteger(0);
        AtomicInteger engine2Slots = new AtomicInteger(0);
        
        // Engine 1 acquisition
        new Thread(() -> {
            SlotAssignment assignment = slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 0).block();
            engine1Slots.set(assignment.getSlotCount());
            latch.countDown();
        }).start();
        
        // Engine 2 acquisition
        new Thread(() -> {
            SlotAssignment assignment = slotManager.acquireSlots(engine2Id, actualTopic, TEST_GROUP, 0).block();
            engine2Slots.set(assignment.getSlotCount());
            latch.countDown();
        }).start();
        
        assertTrue(latch.await(10, TimeUnit.SECONDS));
        
        // Both engines should have some slots
        assertTrue(engine1Slots.get() > 0);
        assertTrue(engine2Slots.get() > 0);
        
        // Total should equal partition count
        List<KafkaSlot> allSlots = slotManager.getSlotsForTopic(actualTopic, TEST_GROUP).block();
        assertEquals(allSlots.size(), engine1Slots.get() + engine2Slots.get());
        
        LOG.info("Engine 1 got {} slots, Engine 2 got {} slots", 
                engine1Slots.get(), engine2Slots.get());
    }
    
    @Test
    @Order(4)
    @DisplayName("Should handle heartbeat expiration")
    void testHeartbeatExpiration() throws Exception {
        // Register engine with short heartbeat timeout
        slotManager.registerEngine(engine1Id, 10).block();
        
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        // Acquire slots
        SlotAssignment assignment = slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 2).block();
        assertEquals(2, assignment.getSlotCount());
        
        // Wait for heartbeat timeout (configured as 10 seconds in test)
        LOG.info("Waiting for heartbeat timeout...");
        Thread.sleep(12000);
        
        // Check slots are marked as expired
        List<KafkaSlot> slots = slotManager.getSlotsForTopic(actualTopic, TEST_GROUP).block();
        long expiredCount = slots.stream()
                .filter(s -> s.getStatus() == KafkaSlot.SlotStatus.HEARTBEAT_EXPIRED)
                .count();
        assertTrue(expiredCount > 0, "Some slots should have expired heartbeats");
        
        // Another engine should be able to acquire expired slots
        slotManager.registerEngine(engine2Id, 10).block();
        SlotAssignment assignment2 = slotManager.acquireSlots(engine2Id, actualTopic, TEST_GROUP, 0).block();
        assertTrue(assignment2.getSlotCount() > 0, "Engine 2 should acquire expired slots");
    }
    
    @Test
    @Order(5)
    @DisplayName("Should rebalance slots across engines")
    void testSlotRebalancing() throws Exception {
        // Register first engine and let it acquire all slots
        slotManager.registerEngine(engine1Id, 10).block();
        
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        SlotAssignment assignment1 = slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 0).block();
        int totalSlots = assignment1.getSlotCount();
        assertTrue(totalSlots > 0);
        
        // Register second engine
        slotManager.registerEngine(engine2Id, 10).block();
        
        // Trigger rebalance
        slotManager.rebalanceSlots(actualTopic, TEST_GROUP).block();
        
        // Check both engines have slots
        assignment1 = slotManager.getAssignmentsForEngine(engine1Id).block();
        SlotAssignment assignment2 = slotManager.getAssignmentsForEngine(engine2Id).block();
        
        assertTrue(assignment1.getSlotCount() > 0);
        assertTrue(assignment2.getSlotCount() > 0);
        assertEquals(totalSlots, assignment1.getSlotCount() + assignment2.getSlotCount());
        
        LOG.info("After rebalance: Engine 1 has {} slots, Engine 2 has {} slots",
                assignment1.getSlotCount(), assignment2.getSlotCount());
    }
    
    @Test
    @Order(6)
    @DisplayName("Should watch for assignment changes")
    void testAssignmentWatching() throws Exception {
        // Register engine
        slotManager.registerEngine(engine1Id, 10).block();
        
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        // Start watching
        CountDownLatch changeLatch = new CountDownLatch(1);
        AtomicInteger changeCount = new AtomicInteger(0);
        
        slotManager.watchAssignments(engine1Id)
                .take(2) // Take first 2 changes
                .subscribe(
                        assignment -> {
                            LOG.info("Assignment changed: {} slots", assignment.getSlotCount());
                            changeCount.incrementAndGet();
                            if (changeCount.get() >= 2) {
                                changeLatch.countDown();
                            }
                        },
                        error -> LOG.error("Watch error", error)
                );
        
        // Trigger changes
        Thread.sleep(1000); // Let watcher start
        
        // Change 1: Acquire slots
        slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 2).block();
        
        // Change 2: Acquire more slots
        Thread.sleep(1000);
        slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 1).block();
        
        assertTrue(changeLatch.await(15, TimeUnit.SECONDS), "Should receive assignment changes");
        assertEquals(2, changeCount.get());
    }
    
    @Test
    @Order(7)
    @DisplayName("Integration with SlotAwareKafkaListenerManager")
    void testListenerManagerIntegration() {
        // This test would require SlotAwareKafkaListenerManager to be injected
        // For now, we'll test the slot manager provides correct data for it
        
        slotManager.registerEngine(engine1Id, 10).block();
        
        String actualTopic = kafkaTopicService.generateTopicName(TEST_PIPELINE, TEST_STEP, 
                PipelineKafkaTopicService.TopicType.INPUT);
        
        // Acquire slots
        SlotAssignment assignment = slotManager.acquireSlots(engine1Id, actualTopic, TEST_GROUP, 3).block();
        
        // Verify assignment has partition information needed by listener manager
        assertNotNull(assignment);
        assertFalse(assignment.isEmpty());
        
        List<Integer> partitions = assignment.getPartitionsForTopic(actualTopic);
        assertEquals(3, partitions.size());
        
        // Verify each partition is unique
        assertEquals(3, partitions.stream().distinct().count());
    }
    
    @AfterEach
    void tearDown() {
        // Clean up engines
        if (engine1Id != null) {
            slotManager.unregisterEngine(engine1Id).block();
        }
        if (engine2Id != null) {
            slotManager.unregisterEngine(engine2Id).block();
        }
        
        // Clean up topics
        try {
            var topics = kafkaAdmin.listTopics().names().get();
            var testTopics = topics.stream()
                    .filter(t -> t.contains(TEST_PIPELINE))
                    .toList();
            if (!testTopics.isEmpty()) {
                kafkaAdmin.deleteTopics(testTopics).all().get();
            }
        } catch (Exception e) {
            LOG.error("Failed to clean up topics", e);
        }
    }
}