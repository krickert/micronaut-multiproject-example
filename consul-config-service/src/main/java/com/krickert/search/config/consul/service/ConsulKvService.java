package com.krickert.search.config.consul.service;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for interacting with Consul's Key-Value store.
 * Provides methods for reading, writing, and deleting configuration values.
 * Uses transactions for batch operations.
 */
@Singleton
public class ConsulKvService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvService.class);
    private final KeyValueClient keyValueClient;
    private final String configPath;

    /**
     * Creates a new ConsulKvService with the specified KeyValueClient.
     *
     * @param keyValueClient the KeyValueClient to use for KV operations
     * @param configPath the base path for configuration in Consul KV store
     */
    public ConsulKvService(@jakarta.inject.Named("primaryKeyValueClient") KeyValueClient keyValueClient, 
                          @Value("${consul.client.config.path:config/pipeline}") String configPath) {
        this.keyValueClient = keyValueClient;
        this.configPath = configPath;
        LOG.info("ConsulKvService initialized with config path: {}", configPath);
    }

    /**
     * Encodes a key for use with Consul KV store.
     * This method URL-encodes special characters in the key to ensure they are properly handled.
     *
     * @param key the key to encode
     * @return the encoded key
     */
    private String encodeKey(String key) {
        try {
            // Split the key by '/' and encode each part separately
            String[] parts = key.split("/");
            StringBuilder encodedKey = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    encodedKey.append("/");
                }
                // URL encode the part, preserving only forward slashes
                encodedKey.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8.toString())
                        .replace("+", "%20")); // Replace + with %20 for spaces
            }

            LOG.debug("Encoded key '{}' to '{}'", key, encodedKey);
            return encodedKey.toString();
        } catch (Exception e) {
            LOG.warn("Error encoding key: {}. Using original key.", key, e);
            return key;
        }
    }

    /**
     * Gets a value from Consul KV store.
     *
     * @param key the key to get
     * @return a Mono containing an Optional with the value if found, or empty if not found
     */
    public Mono<Optional<String>> getValue(String key) {
        LOG.debug("Getting value for key: {}", key);
        return Mono.fromCallable(() -> {
            try {
                String encodedKey = encodeKey(key);
                Optional<org.kiwiproject.consul.model.kv.Value> valueOpt = keyValueClient.getValue(encodedKey);

                if (valueOpt.isPresent()) {
                    org.kiwiproject.consul.model.kv.Value value = valueOpt.get();
                    return value.getValueAsString();
                } else {
                    LOG.debug("No value found for key: {}", key);
                    return Optional.empty();
                }
            } catch (Exception e) {
                LOG.error("Error getting value for key: {}", key, e);
                return Optional.empty();
            }
        });
    }

    /**
     * Puts a value into Consul KV store.
     *
     * @param key the key to put
     * @param value the value to put
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> putValue(String key, String value) {
        LOG.debug("Putting value for key: {}", key);
        return Mono.fromCallable(() -> {
            try {
                String encodedKey = encodeKey(key);
                boolean success = keyValueClient.putValue(encodedKey, value);

                if (success) {
                    LOG.info("Successfully wrote value to Consul KV for key: {}", key);
                    return true;
                } else {
                    LOG.error("Failed to write value to Consul KV for key: {}", key);
                    return false;
                }
            } catch (Exception e) {
                LOG.error("Error writing value to Consul KV for key: {}", key, e);
                return false;
            }
        });
    }

    /**
     * Puts multiple values into Consul KV store using a transaction.
     *
     * @param keyValueMap a map of keys to values to put
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> putValues(Map<String, String> keyValueMap) {
        LOG.debug("Putting multiple values: {}", keyValueMap.keySet());
        return Mono.fromCallable(() -> {
            try {
                // Use putValues method to put multiple values at once
                List<String> keys = new ArrayList<>();
                for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
                    String encodedKey = encodeKey(entry.getKey());
                    boolean success = keyValueClient.putValue(encodedKey, entry.getValue());
                    if (success) {
                        keys.add(entry.getKey());
                    }
                }

                if (keys.size() == keyValueMap.size()) {
                    LOG.info("Successfully wrote all values to Consul KV: {}", keys);
                    return true;
                } else {
                    LOG.error("Failed to write some values to Consul KV. Wrote: {}, Expected: {}", 
                            keys.size(), keyValueMap.size());
                    return false;
                }
            } catch (Exception e) {
                LOG.error("Error writing multiple values to Consul KV: {}", keyValueMap.keySet(), e);
                return false;
            }
        });
    }

    /**
     * Deletes a key from Consul KV store.
     *
     * @param key the key to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteKey(String key) {
        LOG.debug("Deleting key: {}", key);
        return Mono.fromCallable(() -> {
            try {
                String encodedKey = encodeKey(key);
                keyValueClient.deleteKey(encodedKey);
                LOG.debug("Successfully deleted key: {}", key);
                return true;
            } catch (Exception e) {
                LOG.error("Error deleting key: {}", key, e);
                return false;
            }
        });
    }

    /**
     * Deletes multiple keys from Consul KV store.
     *
     * @param keys a list of keys to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteKeys(List<String> keys) {
        LOG.debug("Deleting multiple keys: {}", keys);
        return Mono.fromCallable(() -> {
            try {
                for (String key : keys) {
                    String encodedKey = encodeKey(key);
                    keyValueClient.deleteKey(encodedKey);
                }
                LOG.info("Successfully deleted multiple keys from Consul KV: {}", keys);
                return true;
            } catch (Exception e) {
                LOG.error("Error deleting multiple keys from Consul KV: {}", keys, e);
                return false;
            }
        });
    }

    /**
     * Gets the full path for a key in Consul KV store.
     *
     * @param key the key
     * @return the full path
     */
    public String getFullPath(String key) {
        if (key.startsWith(configPath)) {
            return key;
        }
        return configPath + (configPath.endsWith("/") ? "" : "/") + key;
    }
}
