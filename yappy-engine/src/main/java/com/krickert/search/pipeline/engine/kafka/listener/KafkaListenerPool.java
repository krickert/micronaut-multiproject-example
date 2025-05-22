package com.krickert.search.pipeline.engine.kafka.listener;

import com.krickert.search.pipeline.engine.PipeStreamEngine;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a pool of Kafka listeners.
 * 
 * This class is responsible for:
 * 1. Creating and registering dynamic Kafka listeners
 * 2. Storing and retrieving listeners by ID
 * 3. Removing listeners when they are no longer needed
 * 
 * The KafkaListenerPool maintains a map of listener IDs to DynamicKafkaListener
 * instances, allowing for efficient lookup and management of listeners.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class KafkaListenerPool {
    private static final Logger log = LoggerFactory.getLogger(KafkaListenerPool.class);
    
    /**
     * Map of listener IDs to DynamicKafkaListener instances.
     */
    private final Map<String, DynamicKafkaListener> listeners = new ConcurrentHashMap<>();
    
    /**
     * Creates and registers a new dynamic Kafka listener.
     * 
     * @param listenerId The ID of the listener
     * @param topic The Kafka topic to listen to
     * @param groupId The consumer group ID
     * @param consumerConfig Additional consumer configuration properties
     * @param pipelineName The name of the pipeline
     * @param stepName The name of the step
     * @param pipeStreamEngine The PipeStreamEngine to forward messages to
     * @return The created listener
     */
    public DynamicKafkaListener createListener(
            String listenerId,
            String topic, 
            String groupId, 
            Map<String, String> consumerConfig,
            String pipelineName,
            String stepName,
            PipeStreamEngine pipeStreamEngine) {
        
        // Check if listener already exists
        DynamicKafkaListener existingListener = listeners.get(listenerId);
        if (existingListener != null) {
            log.info("Listener already exists with ID: {}", listenerId);
            return existingListener;
        }
        
        // Create a new dynamic listener
        DynamicKafkaListener listener = new DynamicKafkaListener(
                listenerId, topic, groupId, consumerConfig, 
                pipelineName, stepName, pipeStreamEngine);
        
        // Store in our pool
        listeners.put(listenerId, listener);
        
        log.info("Created Kafka listener: {} for topic: {}, group: {}", 
                listenerId, topic, groupId);
        
        return listener;
    }
    
    /**
     * Removes a listener.
     * 
     * @param listenerId The ID of the listener to remove
     * @return The removed listener, or null if not found
     */
    public DynamicKafkaListener removeListener(String listenerId) {
        DynamicKafkaListener listener = listeners.remove(listenerId);
        if (listener != null) {
            listener.shutdown();
            log.info("Removed Kafka listener: {}", listenerId);
        } else {
            log.warn("Attempted to remove non-existent listener: {}", listenerId);
        }
        
        return listener;
    }
    
    /**
     * Gets a listener by ID.
     * 
     * @param listenerId The ID of the listener
     * @return The listener, or null if not found
     */
    public DynamicKafkaListener getListener(String listenerId) {
        return listeners.get(listenerId);
    }
    
    /**
     * Gets all listeners.
     * 
     * @return An unmodifiable collection of all listeners
     */
    public Collection<DynamicKafkaListener> getAllListeners() {
        return Collections.unmodifiableCollection(listeners.values());
    }
    
    /**
     * Gets the number of listeners.
     * 
     * @return The number of listeners
     */
    public int getListenerCount() {
        return listeners.size();
    }
    
    /**
     * Checks if a listener exists.
     * 
     * @param listenerId The ID of the listener
     * @return true if the listener exists, false otherwise
     */
    public boolean hasListener(String listenerId) {
        return listeners.containsKey(listenerId);
    }
    
    /**
     * Shuts down all listeners.
     */
    public void shutdownAllListeners() {
        for (DynamicKafkaListener listener : listeners.values()) {
            try {
                listener.shutdown();
            } catch (Exception e) {
                log.error("Error shutting down listener: {}", listener.getListenerId(), e);
            }
        }
        
        listeners.clear();
        log.info("Shut down all Kafka listeners");
    }
}