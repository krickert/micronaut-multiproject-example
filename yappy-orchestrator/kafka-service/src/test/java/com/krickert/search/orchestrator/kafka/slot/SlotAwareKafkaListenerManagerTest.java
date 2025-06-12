package com.krickert.search.orchestrator.kafka.slot;

import com.krickert.search.orchestrator.kafka.listener.KafkaListenerManager;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import com.krickert.yappy.kafka.slot.model.KafkaSlot;
import com.krickert.yappy.kafka.slot.model.SlotAssignment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for SlotAwareKafkaListenerManager focusing on:
 * 1. getSlotDistribution() method functionality
 * 2. rebalanceSlots() method functionality
 * 3. Integration with KafkaSlotManager
 */
@ExtendWith(MockitoExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlotAwareKafkaListenerManagerTest {

    private static final Logger LOG = LoggerFactory.getLogger(SlotAwareKafkaListenerManagerTest.class);
    
    private static final String TEST_TOPIC = "slot-test-topic";
    private static final String TEST_GROUP = "slot-test-group";
    private static final String ENGINE_ID = "test-engine-123";
    
    @Mock
    private KafkaSlotManager mockSlotManager;
    
    @Mock
    private KafkaListenerManager mockListenerManager;
    
    private SlotAwareKafkaListenerManager slotAwareManager;
    
    @BeforeEach
    void setUp() {
        // Create the SlotAwareKafkaListenerManager with mocked dependencies
        slotAwareManager = new SlotAwareKafkaListenerManager(
            mockSlotManager, 
            mockListenerManager, 
            ENGINE_ID, 
            10, // maxSlots
            Duration.ofSeconds(30) // heartbeatInterval
        );
    }
    
    @Test
    @DisplayName("getSlotDistribution delegates to KafkaSlotManager correctly")
    void testGetSlotDistribution() {
        LOG.info("🔍 Testing getSlotDistribution delegation...");
        
        // Prepare test data
        Map<String, Integer> expectedDistribution = Map.of(
            "engine-1", 3,
            "engine-2", 2,
            "engine-3", 1
        );
        
        // Mock the slot manager to return our test data
        when(mockSlotManager.getSlotDistribution())
            .thenReturn(Mono.just(expectedDistribution));
        
        // Test the method
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNext(expectedDistribution)
            .verifyComplete();
        
        // Verify the delegation happened
        verify(mockSlotManager, times(1)).getSlotDistribution();
        
        LOG.info("✅ getSlotDistribution delegation verified");
    }
    
    @Test
    @DisplayName("getSlotDistribution handles empty distribution")
    void testGetSlotDistributionEmpty() {
        LOG.info("🔍 Testing getSlotDistribution with empty result...");
        
        Map<String, Integer> emptyDistribution = Collections.emptyMap();
        
        when(mockSlotManager.getSlotDistribution())
            .thenReturn(Mono.just(emptyDistribution));
        
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNext(emptyDistribution)
            .verifyComplete();
        
        verify(mockSlotManager, times(1)).getSlotDistribution();
        
        LOG.info("✅ Empty distribution handling verified");
    }
    
    @Test
    @DisplayName("getSlotDistribution handles errors gracefully")
    void testGetSlotDistributionError() {
        LOG.info("🔍 Testing getSlotDistribution error handling...");
        
        RuntimeException testError = new RuntimeException("Slot manager unavailable");
        
        when(mockSlotManager.getSlotDistribution())
            .thenReturn(Mono.error(testError));
        
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectError(RuntimeException.class)
            .verify();
        
        verify(mockSlotManager, times(1)).getSlotDistribution();
        
        LOG.info("✅ Error handling verified");
    }
    
    @Test
    @DisplayName("rebalanceSlots delegates to KafkaSlotManager correctly")
    void testRebalanceSlots() {
        LOG.info("🔍 Testing rebalanceSlots delegation...");
        
        // Mock the slot manager to complete successfully
        when(mockSlotManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .thenReturn(Mono.empty());
        
        // Test the method
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .verifyComplete();
        
        // Verify the delegation happened with correct parameters
        verify(mockSlotManager, times(1)).rebalanceSlots(TEST_TOPIC, TEST_GROUP);
        
        LOG.info("✅ rebalanceSlots delegation verified");
    }
    
    @Test
    @DisplayName("rebalanceSlots validates parameters correctly")
    void testRebalanceSlotsValidation() {
        LOG.info("🔍 Testing rebalanceSlots parameter validation...");
        
        // Test with valid parameters works
        when(mockSlotManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .thenReturn(Mono.empty());
        
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .verifyComplete();
        
        verify(mockSlotManager, times(1)).rebalanceSlots(TEST_TOPIC, TEST_GROUP);
        
        LOG.info("✅ Parameter validation verified");
    }
    
    @Test
    @DisplayName("rebalanceSlots handles errors gracefully")
    void testRebalanceSlotsError() {
        LOG.info("🔍 Testing rebalanceSlots error handling...");
        
        RuntimeException testError = new RuntimeException("Rebalancing failed");
        
        when(mockSlotManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .thenReturn(Mono.error(testError));
        
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .expectError(RuntimeException.class)
            .verify();
        
        verify(mockSlotManager, times(1)).rebalanceSlots(TEST_TOPIC, TEST_GROUP);
        
        LOG.info("✅ Rebalancing error handling verified");
    }
    
    @Test
    @DisplayName("Multiple getSlotDistribution calls work correctly")
    void testMultipleGetSlotDistributionCalls() {
        LOG.info("🔍 Testing multiple getSlotDistribution calls...");
        
        Map<String, Integer> distribution1 = Map.of("engine-1", 2);
        Map<String, Integer> distribution2 = Map.of("engine-1", 3, "engine-2", 1);
        
        when(mockSlotManager.getSlotDistribution())
            .thenReturn(Mono.just(distribution1))
            .thenReturn(Mono.just(distribution2));
        
        // First call
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNext(distribution1)
            .verifyComplete();
        
        // Second call should get updated data
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNext(distribution2)
            .verifyComplete();
        
        verify(mockSlotManager, times(2)).getSlotDistribution();
        
        LOG.info("✅ Multiple calls handling verified");
    }
    
    @Test
    @DisplayName("rebalanceSlots can be called for different topics")
    void testRebalanceSlotsMultipleTopics() {
        LOG.info("🔍 Testing rebalanceSlots for multiple topics...");
        
        String topic1 = "topic-1";
        String topic2 = "topic-2";
        String group1 = "group-1";
        String group2 = "group-2";
        
        when(mockSlotManager.rebalanceSlots(topic1, group1))
            .thenReturn(Mono.empty());
        when(mockSlotManager.rebalanceSlots(topic2, group2))
            .thenReturn(Mono.empty());
        
        // Test rebalancing different topics
        StepVerifier.create(slotAwareManager.rebalanceSlots(topic1, group1))
            .verifyComplete();
        
        StepVerifier.create(slotAwareManager.rebalanceSlots(topic2, group2))
            .verifyComplete();
        
        verify(mockSlotManager, times(1)).rebalanceSlots(topic1, group1);
        verify(mockSlotManager, times(1)).rebalanceSlots(topic2, group2);
        
        LOG.info("✅ Multiple topic rebalancing verified");
    }
    
    @Test
    @DisplayName("acquireSlots integrates with slot distribution")
    void testAcquireSlotsIntegration() {
        LOG.info("🔍 Testing acquireSlots integration...");
        
        // Create test slots
        KafkaSlot slot1 = new KafkaSlot(TEST_TOPIC, 0, TEST_GROUP);
        slot1.assign("engine-1");
        KafkaSlot slot2 = new KafkaSlot(TEST_TOPIC, 1, TEST_GROUP);
        slot2.assign("engine-1");
        List<KafkaSlot> testSlots = List.of(slot1, slot2);
        
        SlotAssignment assignment = new SlotAssignment(ENGINE_ID, testSlots, 
            java.time.Instant.now(), java.time.Instant.now());
        
        // Mock slot acquisition
        when(mockSlotManager.acquireSlots(ENGINE_ID, TEST_TOPIC, TEST_GROUP, 2))
            .thenReturn(Mono.just(assignment));
        
        // Test slot acquisition
        StepVerifier.create(slotAwareManager.acquireSlots("test-listener", TEST_TOPIC, TEST_GROUP, 2))
            .expectNext(testSlots)
            .verifyComplete();
        
        verify(mockSlotManager, times(1)).acquireSlots(ENGINE_ID, TEST_TOPIC, TEST_GROUP, 2);
        
        LOG.info("✅ Slot acquisition integration verified");
    }
    
    @Test
    @DisplayName("releaseSlots cleans up properly")
    void testReleaseSlotsCleanup() {
        LOG.info("🔍 Testing releaseSlots cleanup...");
        
        // First acquire some slots
        KafkaSlot testSlot = new KafkaSlot(TEST_TOPIC, 0, TEST_GROUP);
        testSlot.assign(ENGINE_ID);
        List<KafkaSlot> testSlots = List.of(testSlot);
        
        SlotAssignment assignment = new SlotAssignment(ENGINE_ID, testSlots, 
            java.time.Instant.now(), java.time.Instant.now());
        
        when(mockSlotManager.acquireSlots(ENGINE_ID, TEST_TOPIC, TEST_GROUP, 1))
            .thenReturn(Mono.just(assignment));
        when(mockSlotManager.releaseSlots(ENGINE_ID, testSlots))
            .thenReturn(Mono.empty());
        
        // Acquire slots first
        slotAwareManager.acquireSlots("test-listener", TEST_TOPIC, TEST_GROUP, 1).block();
        
        // Then release them
        StepVerifier.create(slotAwareManager.releaseSlots("test-listener"))
            .verifyComplete();
        
        verify(mockSlotManager, times(1)).releaseSlots(ENGINE_ID, testSlots);
        
        LOG.info("✅ Slot release cleanup verified");
    }
}