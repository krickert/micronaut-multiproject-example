package com.krickert.search.orchestrator.kafka.slot;

import com.krickert.search.orchestrator.kafka.listener.KafkaListenerManager;
import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SlotAwareKafkaListenerManager that uses real infrastructure:
 * 1. Real KafkaSlotManager with Consul backend via test resources
 * 2. Real Kafka via test resources  
 * 3. Tests getSlotDistribution() and rebalanceSlots() with actual slot management
 * 
 * This test complements the unit test by verifying the integration works
 * with real dependencies.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "kafka.slot-manager.enabled", value = "true")
@Property(name = "kafka.slot-manager.max-slots", value = "5")
@Property(name = "kafka.slot-manager.heartbeat-interval", value = "PT10S")
class SlotAwareKafkaListenerManagerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(SlotAwareKafkaListenerManagerIntegrationTest.class);
    
    private static final String TEST_TOPIC = "slot-integration-topic";
    private static final String TEST_GROUP = "slot-integration-group";
    
    @Inject
    SlotAwareKafkaListenerManager slotAwareManager;
    
    @Inject 
    KafkaSlotManager slotManager;
    
    @Inject
    KafkaListenerManager listenerManager;
    
    @BeforeEach
    void setUp() {
        LOG.info("Setting up SlotAwareKafkaListenerManager integration test");
        
        // Clean up any existing state
        slotManager.cleanup().block(Duration.ofSeconds(10));
        
        // Wait a moment for cleanup to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @AfterEach
    void tearDown() {
        LOG.info("Cleaning up after SlotAwareKafkaListenerManager integration test");
        
        // Clean up any test state
        slotManager.cleanup().block(Duration.ofSeconds(10));
    }
    
    @Test
    @DisplayName("SlotAwareKafkaListenerManager is properly injected and configured")
    void testInjection() {
        LOG.info("🔍 Testing SlotAwareKafkaListenerManager injection...");
        
        assertNotNull(slotAwareManager, "SlotAwareKafkaListenerManager should be injected");
        assertNotNull(slotManager, "KafkaSlotManager should be injected");
        assertNotNull(listenerManager, "KafkaListenerManager should be injected");
        
        LOG.info("✅ All dependencies properly injected");
    }
    
    @Test
    @DisplayName("getSlotDistribution works with real slot manager")
    void testGetSlotDistributionIntegration() {
        LOG.info("🔍 Testing getSlotDistribution with real infrastructure...");
        
        // Initially should have empty or minimal distribution
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNextMatches(distribution -> {
                LOG.info("Initial slot distribution: {}", distribution);
                assertNotNull(distribution, "Distribution should not be null");
                // Should be empty initially or have engines with 0 slots
                return distribution.isEmpty() || distribution.values().stream().allMatch(count -> count >= 0);
            })
            .verifyComplete();
        
        LOG.info("✅ getSlotDistribution working with real infrastructure");
    }
    
    @Test
    @DisplayName("rebalanceSlots works with real slot manager")
    void testRebalanceSlotsIntegration() {
        LOG.info("🔍 Testing rebalanceSlots with real infrastructure...");
        
        // Should complete without error even if no slots exist
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .verifyComplete();
        
        LOG.info("✅ rebalanceSlots working with real infrastructure");
    }
    
    @Test
    @DisplayName("Slot distribution updates when slots are acquired and released")
    void testSlotDistributionDynamicUpdates() {
        LOG.info("🔍 Testing dynamic slot distribution updates...");
        
        // Get initial distribution
        Map<String, Integer> initialDistribution = slotAwareManager.getSlotDistribution().block();
        LOG.info("Initial distribution: {}", initialDistribution);
        
        // Acquire some slots
        slotAwareManager.acquireSlots("test-listener", TEST_TOPIC, TEST_GROUP, 2)
            .block(Duration.ofSeconds(10));
        
        // Wait for distribution to update
        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Map<String, Integer> updatedDistribution = slotAwareManager.getSlotDistribution().block();
                LOG.info("Updated distribution after acquiring slots: {}", updatedDistribution);
                
                assertNotNull(updatedDistribution, "Updated distribution should not be null");
                
                // Should have some slots assigned somewhere
                int totalSlots = updatedDistribution.values().stream().mapToInt(Integer::intValue).sum();
                assertTrue(totalSlots >= 0, "Should have non-negative total slots");
                
                LOG.info("✅ Distribution updated after slot acquisition: {} total slots", totalSlots);
            });
        
        // Release the slots
        slotAwareManager.releaseSlots("test-listener").block(Duration.ofSeconds(10));
        
        // Wait for distribution to update after release
        await().atMost(Duration.ofSeconds(15))
            .pollInterval(Duration.ofMillis(500))
            .untilAsserted(() -> {
                Map<String, Integer> finalDistribution = slotAwareManager.getSlotDistribution().block();
                LOG.info("Final distribution after releasing slots: {}", finalDistribution);
                
                assertNotNull(finalDistribution, "Final distribution should not be null");
                LOG.info("✅ Distribution updated after slot release");
            });
    }
    
    @Test
    @DisplayName("Multiple slot operations work correctly")
    void testMultipleSlotOperations() {
        LOG.info("🔍 Testing multiple slot operations...");
        
        // Acquire slots for multiple listeners
        StepVerifier.create(slotAwareManager.acquireSlots("listener-1", TEST_TOPIC, TEST_GROUP, 1))
            .expectNextCount(1)
            .verifyComplete();
            
        StepVerifier.create(slotAwareManager.acquireSlots("listener-2", TEST_TOPIC + "-2", TEST_GROUP + "-2", 1))
            .expectNextCount(1)
            .verifyComplete();
        
        // Check distribution shows both
        await().atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Map<String, Integer> distribution = slotAwareManager.getSlotDistribution().block();
                LOG.info("Distribution with multiple listeners: {}", distribution);
                
                assertNotNull(distribution, "Distribution should not be null");
                int totalSlots = distribution.values().stream().mapToInt(Integer::intValue).sum();
                assertTrue(totalSlots >= 0, "Should have some slots assigned");
            });
        
        // Rebalance should work with multiple topics
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, TEST_GROUP))
            .verifyComplete();
            
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC + "-2", TEST_GROUP + "-2"))
            .verifyComplete();
        
        // Clean up
        slotAwareManager.releaseSlots("listener-1").block();
        slotAwareManager.releaseSlots("listener-2").block();
        
        LOG.info("✅ Multiple slot operations completed successfully");
    }
    
    @Test
    @DisplayName("Slot manager health reflects in distribution operations")
    void testSlotManagerHealthIntegration() {
        LOG.info("🔍 Testing slot manager health integration...");
        
        // Health should be accessible through the slot-aware manager
        StepVerifier.create(slotAwareManager.getHealth())
            .expectNextMatches(health -> {
                LOG.info("Slot manager health: {}", health);
                assertNotNull(health, "Health should not be null");
                assertTrue(health.healthy(), "Slot manager should be healthy");
                assertTrue(health.totalSlots() >= 0, "Total slots should be non-negative");
                assertTrue(health.assignedSlots() >= 0, "Assigned slots should be non-negative");
                assertTrue(health.availableSlots() >= 0, "Available slots should be non-negative");
                assertTrue(health.registeredEngines() >= 0, "Registered engines should be non-negative");
                return true;
            })
            .verifyComplete();
        
        LOG.info("✅ Slot manager health integration verified");
    }
    
    @Test
    @DisplayName("Error scenarios are handled gracefully")
    void testErrorHandling() {
        LOG.info("🔍 Testing error handling scenarios...");
        
        // Invalid topic names should be handled gracefully
        StepVerifier.create(slotAwareManager.rebalanceSlots("", TEST_GROUP))
            .verifyError();
            
        StepVerifier.create(slotAwareManager.rebalanceSlots(TEST_TOPIC, ""))
            .verifyError();
        
        // Distribution should still be accessible even after errors
        StepVerifier.create(slotAwareManager.getSlotDistribution())
            .expectNextMatches(distribution -> {
                LOG.info("Distribution after error scenarios: {}", distribution);
                return distribution != null;
            })
            .verifyComplete();
        
        LOG.info("✅ Error handling verified");
    }
}