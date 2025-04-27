package com.krickert.search.test.platform.consul;

import com.krickert.search.test.consul.ConsulContainer;
import com.krickert.search.test.consul.ConsulTestHelper;
import com.krickert.search.test.platform.kafka.TestContainerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for Consul tests.
 * This class provides common functionality for Consul tests.
 */
public abstract class AbstractConsulTest implements ConsulTest {
    protected static final Logger log = LoggerFactory.getLogger(AbstractConsulTest.class);
    
    // Consul container
    protected static final ConsulContainer consulContainer = new ConsulContainer();
    
    // Test container manager
    protected static final TestContainerManager containerManager = TestContainerManager.getInstance();
    
    /**
     * Set up the test environment before each test.
     * This method starts the container.
     */
    @BeforeEach
    public void setUp() {
        startContainer();
    }
    
    /**
     * Clean up the test environment after each test.
     * This method resets the container.
     */
    @AfterEach
    public void tearDown() {
        resetContainer();
        // Force garbage collection to help clean up resources
        System.gc();
    }
    
    /**
     * Get the endpoint URL for the Consul server.
     * 
     * @return the endpoint URL as a string
     */
    @Override
    public String getEndpoint() {
        return consulContainer.getEndpoint();
    }
    
    /**
     * Get the host and port of the Consul server in the format "host:port".
     * 
     * @return the host and port as a string
     */
    @Override
    public String getHostAndPort() {
        return consulContainer.getHostAndPort();
    }
    
    /**
     * Start the Consul container.
     * This method is idempotent.
     */
    @Override
    public void startContainer() {
        if (!isContainerRunning()) {
            log.info("Starting Consul container...");
            consulContainer.start();
        }
        
        // Set up properties
        setupConsulProperties();
    }
    
    /**
     * Check if the Consul container is running.
     * 
     * @return true if the container is running, false otherwise
     */
    @Override
    public boolean isContainerRunning() {
        return consulContainer.isRunning();
    }
    
    /**
     * Reset the Consul state between tests.
     * This method should clean up any resources that might cause interference between tests.
     */
    @Override
    public void resetContainer() {
        log.info("Resetting Consul container...");
        // Clear the properties
        containerManager.clearProperties();
        // Set up properties again
        setupConsulProperties();
    }
    
    /**
     * Load configuration into Consul.
     * This method loads configuration from a properties file into Consul.
     * 
     * @param filename the name of the properties file to load
     * @param prefix the prefix to use for the keys in Consul
     * @return true if the operation was successful, false otherwise
     */
    @Override
    public boolean loadConfig(String filename, String prefix) {
        log.info("Loading configuration from {} with prefix {}", filename, prefix);
        // Use ConsulTestHelper to load the configuration
        ConsulTestHelper helper = new ConsulTestHelper();
        return helper.loadPropertiesFile(filename, prefix);
    }
    
    /**
     * Set up Consul properties.
     * This method sets up properties for Consul.
     */
    protected void setupConsulProperties() {
        Map<String, String> props = new HashMap<>();
        
        // Get properties from the Consul container
        props.putAll(consulContainer.getProperties());
        
        // Add properties to container manager
        containerManager.setProperties(props);
    }
    
    /**
     * Get the properties for the test.
     * This method returns the properties from the container manager.
     * 
     * @return the properties map
     */
    @Override
    public Map<String, String> getProperties() {
        return containerManager.getProperties();
    }
}