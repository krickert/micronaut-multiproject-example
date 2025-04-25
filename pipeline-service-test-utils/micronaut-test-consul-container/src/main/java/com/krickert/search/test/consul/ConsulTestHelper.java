package com.krickert.search.test.consul;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.discovery.consul.client.v1.ConsulClient;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Helper class for testing with Consul.
 * This class provides utility methods for loading test data into Consul and retrieving it.
 */
@Requires(env = "test")
@Singleton
public class ConsulTestHelper {
    private static final Logger log = LoggerFactory.getLogger(ConsulTestHelper.class);

    /**
     * -- GETTER --
     *  Get the Consul container.
     *
     */
    @Getter
    @Inject
    private ConsulContainer consulContainer;

    /**
     * -- GETTER --
     *  Get the Consul client.
     *
     */
    @Getter
    @Inject
    private ConsulClient consulClient;

    @SuppressWarnings("MnInjectionPoints")
    @Inject
    private Environment environment;

    /**
     * Load a properties file into Consul.
     * 
     * @param filename the name of the properties file to load
     * @param prefix the prefix to use for the keys in Consul
     * @return true if the operation was successful, false otherwise
     */
    public boolean loadPropertiesFile(String filename, String prefix) {
        Properties properties = new Properties();

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(filename)) {
            if (input == null) {
                log.error("Unable to find properties file: {}", filename);
                return false;
            }

            properties.load(input);
            log.info("Loaded {} properties from file: {}", properties.size(), filename);

            // Store properties in Consul using the Environment
            for (String key : properties.stringPropertyNames()) {
                String consulKey = prefix + "/" + key;
                String value = properties.getProperty(key);
                log.debug("Storing property in Consul: {} = {}", consulKey, value);

                // Use the Environment to store the property
                Map<String, Object> propertyMap = new HashMap<>();
                propertyMap.put(key, value);
                environment.addPropertySource(PropertySource.of(consulKey, propertyMap));
            }

            log.info("Successfully loaded {} properties into Consul with prefix: {}", properties.size(), prefix);
            return true;

        } catch (IOException e) {
            log.error("Error loading properties file: {}", filename, e);
            return false;
        }
    }

    /**
     * Load pipeline configuration properties into Consul.
     * This is a convenience method that loads properties from a file and stores them in Consul
     * under the "config/pipeline" prefix.
     * 
     * @param filename the name of the properties file to load
     * @return true if the operation was successful, false otherwise
     */
    public boolean loadPipelineConfig(String filename) {
        return loadPropertiesFile(filename, "config/pipeline");
    }

    /**
     * Get key-value pairs from Consul with the given prefix.
     * 
     * @param prefix the prefix to search for
     * @return a map of key-value pairs
     */
    public Map<String, String> getKeyValues(String prefix) {
        try {
            log.debug("Getting key-value pairs from Consul with prefix: {}", prefix);

            // Use the Environment to get properties with the given prefix
            Map<String, Object> properties = environment.getProperties(prefix);
            Map<String, String> keyValues = new HashMap<>();

            // Convert properties to a map of strings
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (value != null) {
                    keyValues.put(key, value.toString());
                    log.debug("Found key-value pair: {} = {}", key, value);
                }
            }

            log.debug("Found {} key-value pairs with prefix: {}", keyValues.size(), prefix);
            return keyValues;
        } catch (Exception e) {
            log.error("Error getting key-value pairs from Consul with prefix: {}", prefix, e);
            return new HashMap<>();
        }
    }

    /**
     * Get a specific key-value pair from Consul.
     * 
     * @param key the key to get
     * @return the value associated with the key, or null if not found
     */
    public String getKeyValue(String key) {
        try {
            log.debug("Getting key-value pair from Consul with key: {}", key);

            // Use the Environment to get the property
            String value = environment.getProperty(key, String.class).orElse(null);

            if (value != null) {
                log.debug("Found key-value pair: {} = {}", key, value);
                return value;
            }

            log.debug("Key-value pair not found with key: {}", key);
            return null;
        } catch (Exception e) {
            log.error("Error getting key-value pair from Consul with key: {}", key, e);
            return null;
        }
    }
}
