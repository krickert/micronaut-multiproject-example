package com.krickert.search.test.platform.consul;

import io.micronaut.test.support.TestPropertyProvider;

/**
 * Common interface for Consul test implementations.
 * This interface defines the contract for Consul test implementations
 * that can be used with different test scenarios.
 */
public interface ConsulTest extends TestPropertyProvider {
    
    /**
     * Get the endpoint URL for the Consul server.
     * 
     * @return the endpoint URL as a string
     */
    String getEndpoint();
    
    /**
     * Get the host and port of the Consul server in the format "host:port".
     * 
     * @return the host and port as a string
     */
    String getHostAndPort();
    
    /**
     * Start the Consul container.
     * This method should be idempotent.
     */
    void startContainer();
    
    /**
     * Check if the Consul container is running.
     * 
     * @return true if the container is running, false otherwise
     */
    boolean isContainerRunning();
    
    /**
     * Reset the Consul state between tests.
     * This method should clean up any resources that might cause interference between tests.
     */
    void resetContainer();
    
    /**
     * Load configuration into Consul.
     * This method loads configuration from a properties file into Consul.
     * 
     * @param filename the name of the properties file to load
     * @param prefix the prefix to use for the keys in Consul
     * @return true if the operation was successful, false otherwise
     */
    boolean loadConfig(String filename, String prefix);
}