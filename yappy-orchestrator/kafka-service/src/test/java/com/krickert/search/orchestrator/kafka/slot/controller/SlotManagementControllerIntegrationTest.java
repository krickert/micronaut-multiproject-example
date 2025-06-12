package com.krickert.search.orchestrator.kafka.slot.controller;

import com.krickert.yappy.kafka.slot.KafkaSlotManager;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for SlotManagementController API endpoints.
 * Tests the REST API functionality for slot management operations.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Property(name = "kafka.enabled", value = "true")
@Property(name = "kafka.schema.registry.type", value = "apicurio")
@Property(name = "kafka.slot-manager.enabled", value = "true")
@Property(name = "kafka.slot-manager.max-slots", value = "10")
@Property(name = "kafka.slot-manager.heartbeat-interval", value = "PT30S")
class SlotManagementControllerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(SlotManagementControllerIntegrationTest.class);
    
    private static final String TEST_TOPIC = "controller-test-topic";
    private static final String TEST_GROUP = "controller-test-group";
    
    @Inject
    @Client("/")
    HttpClient client;
    
    @Inject
    KafkaSlotManager slotManager;
    
    @BeforeEach
    void setUp() {
        LOG.info("Setting up SlotManagementController integration test");
        
        // Clean up any existing state
        slotManager.cleanup().block(Duration.ofSeconds(10));
        
        // Wait for cleanup
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    @AfterEach
    void tearDown() {
        LOG.info("Cleaning up after SlotManagementController integration test");
        slotManager.cleanup().block(Duration.ofSeconds(10));
    }
    
    @Test
    @DisplayName("GET /api/kafka/slots/distribution returns slot distribution")
    void testGetSlotDistribution() {
        LOG.info("🔍 Testing GET /api/kafka/slots/distribution...");
        
        var request = HttpRequest.GET("/api/kafka/slots/distribution");
        var response = client.toBlocking().exchange(request, Argument.mapOf(String.class, Integer.class));
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        Map<String, Integer> distribution = response.getBody().orElse(Map.of());
        LOG.info("Received slot distribution: {}", distribution);
        
        // Distribution should be a valid map (can be empty initially)
        assertNotNull(distribution, "Distribution should not be null");
        
        LOG.info("✅ GET /api/kafka/slots/distribution working correctly");
    }
    
    @Test
    @DisplayName("POST /api/kafka/slots/rebalance triggers rebalancing")
    void testRebalanceSlots() {
        LOG.info("🔍 Testing POST /api/kafka/slots/rebalance...");
        
        var request = HttpRequest.POST("/api/kafka/slots/rebalance?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var response = client.toBlocking().exchange(request, SlotManagementController.RebalanceResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        SlotManagementController.RebalanceResponse rebalanceResponse = response.getBody().orElse(null);
        assertNotNull(rebalanceResponse, "Rebalance response should not be null");
        assertEquals("success", rebalanceResponse.status());
        assertTrue(rebalanceResponse.message().contains(TEST_TOPIC), "Message should mention the topic");
        assertTrue(rebalanceResponse.message().contains(TEST_GROUP), "Message should mention the group");
        
        LOG.info("Received rebalance response: {}", rebalanceResponse);
        LOG.info("✅ POST /api/kafka/slots/rebalance working correctly");
    }
    
    @Test
    @DisplayName("POST /api/kafka/slots/grow triggers grow operation")
    void testGrowSlots() {
        LOG.info("🔍 Testing POST /api/kafka/slots/grow...");
        
        var request = HttpRequest.POST("/api/kafka/slots/grow?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var response = client.toBlocking().exchange(request, SlotManagementController.RebalanceResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        SlotManagementController.RebalanceResponse growResponse = response.getBody().orElse(null);
        assertNotNull(growResponse, "Grow response should not be null");
        assertEquals("success", growResponse.status());
        assertTrue(growResponse.message().contains("Grow operation"), "Message should mention grow operation");
        
        LOG.info("Received grow response: {}", growResponse);
        LOG.info("✅ POST /api/kafka/slots/grow working correctly");
    }
    
    @Test
    @DisplayName("POST /api/kafka/slots/shrink triggers shrink operation")
    void testShrinkSlots() {
        LOG.info("🔍 Testing POST /api/kafka/slots/shrink...");
        
        var request = HttpRequest.POST("/api/kafka/slots/shrink?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var response = client.toBlocking().exchange(request, SlotManagementController.RebalanceResponse.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        SlotManagementController.RebalanceResponse shrinkResponse = response.getBody().orElse(null);
        assertNotNull(shrinkResponse, "Shrink response should not be null");
        assertEquals("success", shrinkResponse.status());
        assertTrue(shrinkResponse.message().contains("Shrink operation"), "Message should mention shrink operation");
        
        LOG.info("Received shrink response: {}", shrinkResponse);
        LOG.info("✅ POST /api/kafka/slots/shrink working correctly");
    }
    
    @Test
    @DisplayName("GET /api/kafka/slots/health returns health information")
    void testGetHealth() {
        LOG.info("🔍 Testing GET /api/kafka/slots/health...");
        
        var request = HttpRequest.GET("/api/kafka/slots/health");
        var response = client.toBlocking().exchange(request, KafkaSlotManager.SlotManagerHealth.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        KafkaSlotManager.SlotManagerHealth health = response.getBody().orElse(null);
        assertNotNull(health, "Health should not be null");
        assertTrue(health.healthy(), "Slot manager should be healthy");
        assertTrue(health.totalSlots() >= 0, "Total slots should be non-negative");
        assertTrue(health.assignedSlots() >= 0, "Assigned slots should be non-negative");
        assertTrue(health.availableSlots() >= 0, "Available slots should be non-negative");
        assertTrue(health.registeredEngines() >= 0, "Registered engines should be non-negative");
        
        LOG.info("Received health information: {}", health);
        LOG.info("✅ GET /api/kafka/slots/health working correctly");
    }
    
    @Test
    @DisplayName("GET /api/kafka/slots/engines returns engine information")
    void testGetEngines() {
        LOG.info("🔍 Testing GET /api/kafka/slots/engines...");
        
        var request = HttpRequest.GET("/api/kafka/slots/engines");
        var response = client.toBlocking().exchange(request, Argument.listOf(KafkaSlotManager.EngineInfo.class));
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.getBody().orElse(null), "Response body should not be null");
        
        java.util.List<KafkaSlotManager.EngineInfo> engines = response.getBody().orElse(java.util.Collections.emptyList());
        assertNotNull(engines, "Engines list should not be null");
        assertTrue(engines.size() >= 0, "Should have non-negative number of engines");
        
        LOG.info("Received {} engine(s) information", engines.size());
        for (KafkaSlotManager.EngineInfo engine : engines) {
            LOG.info("Engine: {}", engine);
        }
        LOG.info("✅ GET /api/kafka/slots/engines working correctly");
    }
    
    @Test
    @DisplayName("API handles invalid parameters correctly")
    void testInvalidParameters() {
        LOG.info("🔍 Testing API error handling for invalid parameters...");
        
        // Test missing topic parameter
        var requestMissingTopic = HttpRequest.POST("/api/kafka/slots/rebalance?groupId=" + TEST_GROUP, "");
        try {
            client.toBlocking().exchange(requestMissingTopic, String.class);
            fail("Expected HttpClientResponseException for missing topic parameter");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
        
        // Test missing groupId parameter
        var requestMissingGroup = HttpRequest.POST("/api/kafka/slots/rebalance?topic=" + TEST_TOPIC, "");
        try {
            client.toBlocking().exchange(requestMissingGroup, String.class);
            fail("Expected HttpClientResponseException for missing groupId parameter");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        }
        
        LOG.info("✅ API error handling working correctly");
    }
    
    @Test
    @DisplayName("Complete workflow: distribution -> rebalance -> grow -> shrink")
    void testCompleteWorkflow() {
        LOG.info("🔍 Testing complete slot management workflow...");
        
        // 1. Get initial distribution
        var distributionRequest = HttpRequest.GET("/api/kafka/slots/distribution");
        var distributionResponse = client.toBlocking().exchange(distributionRequest, 
            Argument.mapOf(String.class, Integer.class));
        assertEquals(HttpStatus.OK, distributionResponse.getStatus());
        Map<String, Integer> initialDistribution = distributionResponse.getBody().orElse(Map.of());
        LOG.info("Initial distribution: {}", initialDistribution);
        
        // 2. Trigger rebalance
        var rebalanceRequest = HttpRequest.POST("/api/kafka/slots/rebalance?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var rebalanceResponse = client.toBlocking().exchange(rebalanceRequest, SlotManagementController.RebalanceResponse.class);
        assertEquals(HttpStatus.OK, rebalanceResponse.getStatus());
        LOG.info("Rebalance completed: {}", rebalanceResponse.getBody().orElse(null));
        
        // 3. Trigger grow operation
        var growRequest = HttpRequest.POST("/api/kafka/slots/grow?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var growResponse = client.toBlocking().exchange(growRequest, SlotManagementController.RebalanceResponse.class);
        assertEquals(HttpStatus.OK, growResponse.getStatus());
        LOG.info("Grow completed: {}", growResponse.getBody().orElse(null));
        
        // 4. Trigger shrink operation
        var shrinkRequest = HttpRequest.POST("/api/kafka/slots/shrink?topic=" + TEST_TOPIC + "&groupId=" + TEST_GROUP, "");
        var shrinkResponse = client.toBlocking().exchange(shrinkRequest, SlotManagementController.RebalanceResponse.class);
        assertEquals(HttpStatus.OK, shrinkResponse.getStatus());
        LOG.info("Shrink completed: {}", shrinkResponse.getBody().orElse(null));
        
        // 5. Check final distribution (may be the same as initial, but operation should complete)
        var finalDistributionResponse = client.toBlocking().exchange(distributionRequest, 
            Argument.mapOf(String.class, Integer.class));
        assertEquals(HttpStatus.OK, finalDistributionResponse.getStatus());
        Map<String, Integer> finalDistribution = finalDistributionResponse.getBody().orElse(Map.of());
        LOG.info("Final distribution: {}", finalDistribution);
        
        LOG.info("✅ Complete workflow executed successfully");
    }
}