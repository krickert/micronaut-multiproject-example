package com.krickert.search.test.platform.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager for test container properties.
 * This class manages the properties for Kafka and schema registry containers
 * and ensures they are set before Micronaut starts.
 */
public class TestContainerManager {
    private static final Logger log = LoggerFactory.getLogger(TestContainerManager.class);
    
    // Singleton instance
    private static final TestContainerManager INSTANCE = new TestContainerManager();
    
    // Properties map
    private final Map<String, String> properties = new ConcurrentHashMap<>();
    
    // Registry type property name
    public static final String KAFKA_REGISTRY_TYPE_PROP = "kafka.registry.type";
    public static final String DEFAULT_REGISTRY_TYPE = "apicurio";
    
    // Private constructor to enforce singleton pattern
    private TestContainerManager() {
        log.info("Initializing TestContainerManager");
    }
    
    /**
     * Get the singleton instance of the TestContainerManager.
     * 
     * @return the singleton instance
     */
    public static TestContainerManager getInstance() {
        return INSTANCE;
    }
    
    /**
     * Set a property in the properties map.
     * 
     * @param key the property key
     * @param value the property value
     */
    public void setProperty(String key, String value) {
        properties.put(key, value);
        log.debug("Set property: {} = {}", key, value);
    }
    
    /**
     * Set multiple properties in the properties map.
     * 
     * @param props the properties to set
     */
    public void setProperties(Map<String, String> props) {
        properties.putAll(props);
        log.debug("Added {} properties to the manager", props.size());
    }
    
    /**
     * Get a property from the properties map.
     * 
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getProperty(String key) {
        return properties.get(key);
    }
    
    /**
     * Get a property from the properties map with a default value.
     * 
     * @param key the property key
     * @param defaultValue the default value to return if the property is not found
     * @return the property value, or the default value if not found
     */
    public String getProperty(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }
    
    /**
     * Get the registry type from the properties map.
     * 
     * @return the registry type, or the default registry type if not found
     */
    public String getRegistryType() {
        return getProperty(KAFKA_REGISTRY_TYPE_PROP, DEFAULT_REGISTRY_TYPE);
    }
    
    /**
     * Get all properties as an unmodifiable map.
     * 
     * @return an unmodifiable view of the properties map
     */
    public Map<String, String> getProperties() {
        return new HashMap<>(properties);
    }
    
    /**
     * Clear all properties from the properties map.
     */
    public void clearProperties() {
        properties.clear();
        log.debug("Cleared all properties from the manager");
    }
}