package com.krickert.search.config.consul.service;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.Response;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Service for interacting with Consul's Key-Value store.
 * Provides methods for reading, writing, and deleting configuration values.
 */
@Singleton
public class ConsulKvService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvService.class);
    private final ConsulClient consulClient;
    private final String configPath;

    /**
     * Creates a new ConsulKvService with the specified ConsulClient.
     *
     * @param consulClient the ConsulClient to use for KV operations
     * @param configPath the base path for configuration in Consul KV store
     */
    public ConsulKvService(ConsulClient consulClient, 
                          @Value("${consul.client.config.path:config/pipeline}") String configPath) {
        this.consulClient = consulClient;
        this.configPath = configPath;
        LOG.info("ConsulKvService initialized with config path: {}", configPath);
    }

    /**
     * Gets a value from Consul KV store.
     *
     * @param key the key to get
     * @return a Mono containing an Optional with the value if found, or empty if not found
     */
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

    public Mono<Optional<String>> getValue(String key) {
        LOG.debug("Getting value for key: {}", key);
        return Mono.fromCallable(() -> {
            try {
                String encodedKey = encodeKey(key);
                Response<com.ecwid.consul.v1.kv.model.GetValue> response = consulClient.getKVValue(encodedKey);
                if (response.getValue() != null) {
                    return Optional.ofNullable(response.getValue().getDecodedValue());
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
        return Mono.fromCallable(() -> {
            try {
                // Encode the key to handle special characters
                String encodedKey = encodeKey(key);
                // Use consulClient.setKVValue to write to Consul [24]
                Response<Boolean> response = consulClient.setKVValue(encodedKey, value);
                if (response.getValue()!= null && response.getValue()) {
                    LOG.info("Successfully wrote value to Consul KV for key: {}", key);
                    return true;
                } else {
                    LOG.error("Failed to write value to Consul KV for key: {}. Response: {}", key, response);
                    return false;
                }
            } catch (Exception e) {
                LOG.error("Error writing value to Consul KV for key: {}", key, e);
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
                // Encode the key to handle special characters
                String encodedKey = encodeKey(key);
                // For deleteKVValue, success is indicated by not throwing an exception
                consulClient.deleteKVValue(encodedKey);
                LOG.debug("Successfully deleted key: {}", key);
                return true;
            } catch (Exception e) {
                LOG.error("Error deleting key: {}", key, e);
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
