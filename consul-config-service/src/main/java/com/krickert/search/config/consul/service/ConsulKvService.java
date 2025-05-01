package com.krickert.search.config.consul.service;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.kv.model.GetValue;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream; // Import Stream

/**
 * Service for interacting with Consul's Key-Value store.
 * Provides methods for reading, writing, deleting, and listing configuration values.
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
        // Ensure configPath ends with a slash for consistent prefix handling
        this.configPath = configPath.endsWith("/") ? configPath : configPath + "/";
        LOG.info("ConsulKvService initialized with config path: {}", this.configPath);
    }

    /**
     * Encodes a key path for use with the Consul KV API.
     * This method URL-encodes each segment of the path individually,
     * preserving the '/' separators. This is necessary because characters like
     * '[' and ']' are valid in Consul keys but invalid in URI paths without encoding.
     *
     * @param keyPath the raw key path (e.g., "config/pipeline/service[0]/setting")
     * @return the encoded key path suitable for the Consul client library's API calls.
     */
    private String encodeKeyPath(String keyPath) {
        try {
            // Split by '/', encode each part, then rejoin with '/'
            return Stream.of(keyPath.split("/"))
                    .map(part -> {
                        try {
                            // URLEncoder encodes spaces as '+', replace with %20
                            // Also explicitly don't encode '/' although split should prevent it in `part`
                            return URLEncoder.encode(part, StandardCharsets.UTF_8.toString())
                                    .replace("+", "%20");
                        } catch (Exception e) {
                            LOG.warn("Failed to encode path segment '{}': {}", part, e.getMessage());
                            return part; // Return original part on error
                        }
                    })
                    .collect(Collectors.joining("/"));
        } catch (Exception e) {
            LOG.warn("Error encoding key path: {}. Using original path.", keyPath, e);
            return keyPath; // Fallback to original path on unexpected error
        }
    }


    /**
     * Gets a value from Consul KV store.
     *
     * @param key the key to get (relative to configPath or absolute)
     * @return a Mono containing an Optional with the decoded value if found, or empty if not found/error.
     */
    public Mono<Optional<String>> getValue(String key) {
        String fullPath = getFullPath(key); // Get the full, logical path
        String encodedPath = encodeKeyPath(fullPath); // Encode for API call
        LOG.debug("Getting value for key: '{}', encoded as: '{}'", fullPath, encodedPath);
        return Mono.fromCallable(() -> {
            try {
                Response<GetValue> response = consulClient.getKVValue(encodedPath); // Use encoded path
                if (response.getValue() != null) {
                    return Optional.ofNullable(response.getValue().getDecodedValue());
                } else {
                    LOG.debug("No value found for key: {}", fullPath);
                    return Optional.<String>empty();
                }
            } catch (Exception e) {
                LOG.error("Error getting value for key '{}': {}", fullPath, e.getMessage());
                return Optional.<String>empty();
            }
        }).onErrorReturn(Optional.<String>empty());
    }

    /**
     * Puts a value into Consul KV store.
     *
     * @param key the key to put (relative to configPath or absolute)
     * @param value the value to put
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> putValue(String key, String value) {
        String fullPath = getFullPath(key); // Get the full, logical path
        String encodedPath = encodeKeyPath(fullPath); // Encode for API call
        LOG.debug("Putting value for key: '{}', encoded as: '{}'", fullPath, encodedPath);
        return Mono.fromCallable(() -> {
            try {
                // setKVValue expects raw UTF-8 string for value, encoding is for the key path
                Response<Boolean> response = consulClient.setKVValue(encodedPath, value); // Use encoded path
                boolean success = response.getValue() != null && response.getValue();
                if (success) {
                    LOG.info("Successfully wrote value to Consul KV for key: {}", fullPath);
                } else {
                    LOG.error("Failed to write value to Consul KV for key: {}. Consul response: {}", fullPath, response);
                }
                return success;
            } catch (Exception e) {
                LOG.error("Error writing value to Consul KV for key '{}': {}", fullPath, e.getMessage(), e);
                return false;
            }
        }).onErrorReturn(false);
    }

    /**
     * Deletes a key from Consul KV store.
     *
     * @param key the key to delete (relative to configPath or absolute)
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteKey(String key) {
        String fullPath = getFullPath(key); // Get the full, logical path
        String encodedPath = encodeKeyPath(fullPath); // Encode for API call
        LOG.debug("Deleting key: '{}', encoded as: '{}'", fullPath, encodedPath);
        return Mono.fromCallable(() -> {
            try {
                consulClient.deleteKVValue(encodedPath); // Use encoded path
                LOG.debug("Successfully deleted key: {}", fullPath);
                return true;
            } catch (Exception e) {
                LOG.error("Error deleting key '{}': {}", fullPath, e.getMessage());
                return false;
            }
        }).onErrorReturn(false);
    }

    /**
     * Gets all keys recursively under a given prefix.
     *
     * @param prefix The key prefix to search under (relative to configPath or absolute).
     * @return A Mono emitting a List of keys found under the prefix, or an empty list if none found/error.
     */
    public Mono<List<String>> getKeysRecursive(String prefix) {
        String fullPrefix = getFullPath(prefix); // Get the full, logical path
        // IMPORTANT: Do NOT encode the prefix for getKVKeysOnly, the client library handles prefix matching logic.
        // Ensure the prefix ends with a slash for directory-like listing.
        String consulPrefix = fullPrefix.endsWith("/") ? fullPrefix : fullPrefix + "/";
        LOG.debug("Getting keys recursively under prefix: {}", consulPrefix);
        return Mono.fromCallable(() -> {
            try {
                // Pass the logical prefix directly to the client method
                Response<List<String>> response = consulClient.getKVKeysOnly(consulPrefix);
                List<String> keys = response.getValue();
                if (keys != null) {
                    LOG.debug("Found {} keys under prefix {}", keys.size(), consulPrefix);
                    // Return the keys as reported by Consul (they include the prefix)
                    return keys;
                } else {
                    LOG.debug("No keys found under prefix {}", consulPrefix);
                    return Collections.<String>emptyList();
                }
            } catch (Exception e) {
                LOG.error("Error getting keys for prefix '{}': {}", consulPrefix, e.getMessage(), e);
                return Collections.<String>emptyList();
            }
        }).onErrorReturn(Collections.emptyList());
    }

    /**
     * Gets the full path for a key in Consul KV store, ensuring it's relative to the base configPath.
     * Removes leading/trailing slashes from the input key for consistent joining.
     * Does NOT add a trailing slash, as this should only be done for prefix *queries*.
     *
     * @param key the key (can be relative or absolute)
     * @return the full absolute path starting with configPath, without a trailing slash unless the key is the config path itself.
     */
    public String getFullPath(String key) {
        String trimmedKey = key.trim().replaceAll("^/+|/+$", ""); // Remove leading/trailing slashes
        String trimmedConfigPath = configPath.replaceAll("^/+|/+$", ""); // Base path without trailing slash

        // Handle case where key IS the config path
        if (trimmedKey.isEmpty() || trimmedKey.equals(trimmedConfigPath)) {
            // For the base path itself, we might want the trailing slash for queries,
            // but for get/put/delete, the exact path is usually needed. Return without trailing slash for consistency.
            // If a trailing slash IS needed for a specific operation, add it there.
            return trimmedConfigPath;
        }

        // Check if the key already starts with the base path segment
        if (trimmedKey.startsWith(trimmedConfigPath + "/")) {
            // It's already an absolute path, return it as is (after trimming)
            return trimmedKey;
        }

        // Construct the full path relative to the base config path
        // Avoid double slashes if configPath was "/" or key starts with "/" (handled by trim)
        return trimmedConfigPath.isEmpty() ? trimmedKey : trimmedConfigPath + "/" + trimmedKey;
    }

    /**
     * Gets the configured base configuration path (always ends with /).
     *
     * @return The base config path used by this service.
     */
    public String getConfigPath() {
        return configPath; // Returns the path ensured to end with '/' from constructor
    }
}