package com.krickert.search.test.platform.kafka;

import io.micronaut.test.support.TestPropertyProvider;

/**
 * Common interface for Kafka test implementations.
 * This interface defines the contract for Kafka test implementations
 * that can be used with different schema registry types.
 */
public interface KafkaTest extends TestPropertyProvider {
    
    /**
     * Get the type of schema registry being used.
     * 
     * @return the registry type as a string
     */
    String getRegistryType();
    
    /**
     * Get the endpoint URL for the schema registry.
     * 
     * @return the endpoint URL as a string
     */
    String getRegistryEndpoint();
    
    /**
     * Start the Kafka and schema registry containers.
     * This method should be idempotent.
     */
    void startContainers();
    
    /**
     * Check if the Kafka and schema registry containers are running.
     * 
     * @return true if both containers are running, false otherwise
     */
    boolean areContainersRunning();
    
    /**
     * Reset the Kafka and schema registry state between tests.
     * This method should clean up any resources that might cause interference between tests.
     */
    void resetContainers();
    
    /**
     * Create Kafka topics needed for tests.
     * This ensures that topics are available for tests.
     */
    void createTopics();
    
    /**
     * Delete Kafka topics after tests.
     * This ensures a clean state for the next test.
     */
    void deleteTopics();
}