package com.krickert.search.pipeline.engine.kafka.listener;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the state of Kafka consumers.
 * 
 * This class is responsible for:
 * 1. Storing and retrieving consumer state information
 * 2. Providing access to consumer states
 * 
 * The state information is stored in memory, but could be extended to persist
 * to a database or other storage mechanism.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class ConsumerStateManager {
    private static final Logger log = LoggerFactory.getLogger(ConsumerStateManager.class);
    
    /**
     * Map of consumer IDs to consumer states.
     */
    private final Map<String, ConsumerState> consumerStates = new ConcurrentHashMap<>();
    
    /**
     * Updates the state of a consumer.
     * 
     * @param consumerId The ID of the consumer
     * @param state The new state
     */
    public void updateState(String consumerId, ConsumerState state) {
        if (consumerId == null || consumerId.isBlank()) {
            throw new IllegalArgumentException("Consumer ID cannot be null or blank");
        }
        if (state == null) {
            throw new IllegalArgumentException("Consumer state cannot be null");
        }
        
        consumerStates.put(consumerId, state);
        log.debug("Updated state for consumer: {}, paused: {}", consumerId, state.paused());
    }
    
    /**
     * Gets the state of a consumer.
     * 
     * @param consumerId The ID of the consumer
     * @return The consumer state, or null if not found
     */
    public ConsumerState getState(String consumerId) {
        if (consumerId == null || consumerId.isBlank()) {
            throw new IllegalArgumentException("Consumer ID cannot be null or blank");
        }
        
        return consumerStates.get(consumerId);
    }
    
    /**
     * Gets the states of all consumers.
     * 
     * @return An unmodifiable map of consumer IDs to consumer states
     */
    public Map<String, ConsumerState> getAllStates() {
        return Collections.unmodifiableMap(consumerStates);
    }
    
    /**
     * Removes the state of a consumer.
     * 
     * @param consumerId The ID of the consumer
     * @return The removed state, or null if not found
     */
    public ConsumerState removeState(String consumerId) {
        if (consumerId == null || consumerId.isBlank()) {
            throw new IllegalArgumentException("Consumer ID cannot be null or blank");
        }
        
        ConsumerState removedState = consumerStates.remove(consumerId);
        if (removedState != null) {
            log.debug("Removed state for consumer: {}", consumerId);
        }
        
        return removedState;
    }
    
    /**
     * Checks if a consumer state exists.
     * 
     * @param consumerId The ID of the consumer
     * @return true if the consumer state exists, false otherwise
     */
    public boolean hasState(String consumerId) {
        if (consumerId == null || consumerId.isBlank()) {
            throw new IllegalArgumentException("Consumer ID cannot be null or blank");
        }
        
        return consumerStates.containsKey(consumerId);
    }
    
    /**
     * Gets the number of consumer states.
     * 
     * @return The number of consumer states
     */
    public int getStateCount() {
        return consumerStates.size();
    }
    
    /**
     * Clears all consumer states.
     */
    public void clearAllStates() {
        consumerStates.clear();
        log.debug("Cleared all consumer states");
    }
}