package com.krickert.search.test.platform.consul;

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

    /**
     * Store properties in Consul with the given prefix.
     * 
     * @param properties the properties to store
     * @param prefix the prefix to use for keys
     * @return true if the operation was successful, false otherwise
     */
    public boolean putProperties(Properties properties, String prefix) {
        if (!isContainerRunning()) {
            log.error("Cannot put properties, Consul container is not running.");
            return false;
        }

        boolean allSuccess = true;
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key);
            String fullKey = prefix + key;
            
            log.debug("Storing in Consul KV: '{}' = '{}'", fullKey, value);
            if (!putKv(fullKey, value)) {
                log.error("Failed to store key '{}' with value '{}'", fullKey, value);
                allSuccess = false;
            }
        }
        return allSuccess;
    }

    /**
     * Put a key-value pair into Consul.
     * 
     * @param key the key to store
     * @param value the value to store
     * @return true if the operation was successful, false otherwise
     */
    public boolean putKv(String key, String value) {
        if (!isContainerRunning()) {
            log.error("Cannot put KV, Consul container is not running.");
            return false;
        }

        try {
            // Convert the reactive Publisher to a blocking result using a CompletableFuture
            java.util.concurrent.CompletableFuture<Boolean> future = new java.util.concurrent.CompletableFuture<>();
            
            consulClient.putValue(key, value).subscribe(new org.reactivestreams.Subscriber<Boolean>() {
                @Override
                public void onSubscribe(org.reactivestreams.Subscription s) {
                    s.request(1); // Request one item
                }
                
                @Override
                public void onNext(Boolean success) {
                    future.complete(success);
                }
                
                @Override
                public void onError(Throwable t) {
                    future.completeExceptionally(t);
                }
                
                @Override
                public void onComplete() {
                    if (!future.isDone()) {
                        future.complete(false); // No value received
                    }
                }
            });
            
            Boolean success = future.get(10, java.util.concurrent.TimeUnit.SECONDS); // Timeout after 10 seconds
            
            if (success != null && success) {
                log.debug("Successfully stored KV in Consul: '{}' = '{}'", key, value);
                return true;
            } else {
                log.error("Failed to store KV in Consul: '{}' = '{}'", key, value);
                return false;
            }
        } catch (Exception e) {
            log.error("Error storing KV in Consul: '{}' = '{}'", key, value, e);
            return false;
        }
    }

    /**
     * Delete a key-value pair from Consul.
     * 
     * @param key the key to delete
     * @return true if the operation was successful, false otherwise
     */
    public boolean deleteKv(String key) {
        if (!isContainerRunning()) {
            log.error("Cannot delete KV, Consul container is not running.");
            return false;
        }

        try {
            // Based on ConsulClient API, we'd need to use a direct HTTP call, 
            // but for simplicity we'll use a HTTP delete operation through the container
            boolean success = consulContainer.deleteKv(key);
            if (success) {
                log.debug("Successfully deleted KV from Consul: '{}'", key);
                return true;
            } else {
                log.warn("Failed to delete KV from Consul (key may not exist): '{}'", key);
                return false;
            }
        } catch (Exception e) {
            log.error("Error deleting KV from Consul: '{}'", key, e);
            return false;
        }
    }

    /**
     * Delete a key path recursively from Consul.
     * 
     * @param keyPath the key path to delete
     * @return true if the operation was successful, false otherwise
     */
    public boolean deleteKvRecursive(String keyPath) {
        if (!isContainerRunning()) {
            log.error("Cannot delete KV path recursively, Consul container is not running.");
            return false;
        }

        // Delegate to the container which can execute the consul CLI command
        return consulContainer.deleteKvRecursive(keyPath);
    }

    /**
     * Check if the Consul container is running.
     * 
     * @return true if the container is running, false otherwise
     */
    public boolean isContainerRunning() {
        return consulContainer != null && consulContainer.isRunning();
    }
}
