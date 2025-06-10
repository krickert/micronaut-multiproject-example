package com.krickert.search.pipeline.engine.kafka.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConsumerStateManager}.
 */
class ConsumerStateManagerTest {

    private ConsumerStateManager stateManager;
    private static final String CONSUMER_ID = "test-consumer";
    private static final String TOPIC = "test-topic";
    private static final String GROUP_ID = "test-group";

    @BeforeEach
    void setUp() {
        stateManager = new ConsumerStateManager();
    }

    /**
     * Test that updateState correctly stores a consumer state.
     */
    @Test
    void testUpdateState() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Update the state
        stateManager.updateState(CONSUMER_ID, state);
        
        // Verify the state was stored
        assertEquals(state, stateManager.getState(CONSUMER_ID));
    }

    /**
     * Test that updateState throws an exception when consumerId is null.
     */
    @Test
    void testUpdateStateWithNullConsumerId() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Verify that an exception is thrown when consumerId is null
        assertThrows(IllegalArgumentException.class, () -> stateManager.updateState(null, state));
    }

    /**
     * Test that updateState throws an exception when state is null.
     */
    @Test
    void testUpdateStateWithNullState() {
        // Verify that an exception is thrown when state is null
        assertThrows(IllegalArgumentException.class, () -> stateManager.updateState(CONSUMER_ID, null));
    }

    /**
     * Test that getState returns the correct state.
     */
    @Test
    void testGetState() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Update the state
        stateManager.updateState(CONSUMER_ID, state);
        
        // Verify getState returns the correct state
        assertEquals(state, stateManager.getState(CONSUMER_ID));
    }

    /**
     * Test that getState returns null for a non-existent consumer.
     */
    @Test
    void testGetStateForNonExistentConsumer() {
        // Verify getState returns null for a non-existent consumer
        assertNull(stateManager.getState("non-existent-consumer"));
    }

    /**
     * Test that getState throws an exception when consumerId is null.
     */
    @Test
    void testGetStateWithNullConsumerId() {
        // Verify that an exception is thrown when consumerId is null
        assertThrows(IllegalArgumentException.class, () -> stateManager.getState(null));
    }

    /**
     * Test that getAllStates returns all states.
     */
    @Test
    void testGetAllStates() {
        // Create consumer states
        ConsumerState state1 = new ConsumerState("consumer1", TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        ConsumerState state2 = new ConsumerState("consumer2", TOPIC, GROUP_ID, true, Instant.now(), Collections.emptyMap());
        
        // Update the states
        stateManager.updateState("consumer1", state1);
        stateManager.updateState("consumer2", state2);
        
        // Verify getAllStates returns all states
        Map<String, ConsumerState> allStates = stateManager.getAllStates();
        assertEquals(2, allStates.size());
        assertEquals(state1, allStates.get("consumer1"));
        assertEquals(state2, allStates.get("consumer2"));
    }

    /**
     * Test that getAllStates returns an unmodifiable map.
     */
    @Test
    void testGetAllStatesReturnsUnmodifiableMap() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Update the state
        stateManager.updateState(CONSUMER_ID, state);
        
        // Get all states
        Map<String, ConsumerState> allStates = stateManager.getAllStates();
        
        // Verify that the map is unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> allStates.put("new-consumer", state));
    }

    /**
     * Test that removeState correctly removes a consumer state.
     */
    @Test
    void testRemoveState() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Update the state
        stateManager.updateState(CONSUMER_ID, state);
        
        // Remove the state
        ConsumerState removedState = stateManager.removeState(CONSUMER_ID);
        
        // Verify the state was removed
        assertEquals(state, removedState);
        assertNull(stateManager.getState(CONSUMER_ID));
    }

    /**
     * Test that removeState returns null for a non-existent consumer.
     */
    @Test
    void testRemoveStateForNonExistentConsumer() {
        // Verify removeState returns null for a non-existent consumer
        assertNull(stateManager.removeState("non-existent-consumer"));
    }

    /**
     * Test that removeState throws an exception when consumerId is null.
     */
    @Test
    void testRemoveStateWithNullConsumerId() {
        // Verify that an exception is thrown when consumerId is null
        assertThrows(IllegalArgumentException.class, () -> stateManager.removeState(null));
    }

    /**
     * Test that hasState correctly reports whether a consumer state exists.
     */
    @Test
    void testHasState() {
        // Create a consumer state
        ConsumerState state = new ConsumerState(CONSUMER_ID, TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        
        // Update the state
        stateManager.updateState(CONSUMER_ID, state);
        
        // Verify hasState returns true for an existing consumer
        assertTrue(stateManager.hasState(CONSUMER_ID));
        
        // Verify hasState returns false for a non-existent consumer
        assertFalse(stateManager.hasState("non-existent-consumer"));
    }

    /**
     * Test that hasState throws an exception when consumerId is null.
     */
    @Test
    void testHasStateWithNullConsumerId() {
        // Verify that an exception is thrown when consumerId is null
        assertThrows(IllegalArgumentException.class, () -> stateManager.hasState(null));
    }

    /**
     * Test that getStateCount returns the correct count.
     */
    @Test
    void testGetStateCount() {
        // Verify initial count is 0
        assertEquals(0, stateManager.getStateCount());
        
        // Create consumer states
        ConsumerState state1 = new ConsumerState("consumer1", TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        ConsumerState state2 = new ConsumerState("consumer2", TOPIC, GROUP_ID, true, Instant.now(), Collections.emptyMap());
        
        // Update the states
        stateManager.updateState("consumer1", state1);
        stateManager.updateState("consumer2", state2);
        
        // Verify count is 2
        assertEquals(2, stateManager.getStateCount());
        
        // Remove a state
        stateManager.removeState("consumer1");
        
        // Verify count is 1
        assertEquals(1, stateManager.getStateCount());
    }

    /**
     * Test that clearAllStates correctly clears all states.
     */
    @Test
    void testClearAllStates() {
        // Create consumer states
        ConsumerState state1 = new ConsumerState("consumer1", TOPIC, GROUP_ID, false, Instant.now(), Collections.emptyMap());
        ConsumerState state2 = new ConsumerState("consumer2", TOPIC, GROUP_ID, true, Instant.now(), Collections.emptyMap());
        
        // Update the states
        stateManager.updateState("consumer1", state1);
        stateManager.updateState("consumer2", state2);
        
        // Verify states exist
        assertEquals(2, stateManager.getStateCount());
        
        // Clear all states
        stateManager.clearAllStates();
        
        // Verify all states are cleared
        assertEquals(0, stateManager.getStateCount());
        assertNull(stateManager.getState("consumer1"));
        assertNull(stateManager.getState("consumer2"));
    }
}