package com.krickert.search.config.consul.util;

import jakarta.inject.Singleton;
import org.kiwiproject.consul.KeyValueClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for Consul-related test operations.
 * Provides methods to reset Consul state between tests.
 */
@Singleton
public class ConsulTestUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulTestUtils.class);
    private final KeyValueClient keyValueClient;

    /**
     * Creates a new ConsulTestUtils with the specified KeyValueClient.
     *
     * @param keyValueClient the KeyValueClient to use for KV operations
     */
    public ConsulTestUtils(KeyValueClient keyValueClient) {
        this.keyValueClient = keyValueClient;
    }

    /**
     * Deletes all keys with a given prefix from Consul KV store.
     * This is useful for cleaning up after tests.
     *
     * @param prefix the prefix of the keys to delete
     * @return true if the operation was successful, false otherwise
     */
    public boolean deleteKeysWithPrefix(String prefix) {
        LOG.debug("Deleting keys with prefix: {}", prefix);
        try {
            keyValueClient.deleteKeys(prefix);
            LOG.debug("Successfully deleted keys with prefix: {}", prefix);
            return true;
        } catch (Exception e) {
            LOG.error("Error deleting keys with prefix: {}", prefix, e);
            return false;
        }
    }

    /**
     * Resets Consul state by deleting all keys under the config path.
     * This is useful for ensuring a clean state between tests.
     *
     * @param configPath the base path for configuration in Consul KV store
     * @return true if the operation was successful, false otherwise
     */
    public boolean resetConsulState(String configPath) {
        LOG.info("Resetting Consul state by deleting all keys under: {}", configPath);

        // First attempt to delete keys
        boolean result = deleteKeysWithPrefix(configPath);

        // Verify that keys were actually deleted
        try {
            java.util.List<String> remainingKeys = keyValueClient.getKeys(configPath);
            if (remainingKeys != null && !remainingKeys.isEmpty()) {
                LOG.warn("Keys still exist after deletion attempt: {}", remainingKeys);
                // Try one more time with a small delay
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                keyValueClient.deleteKeys(configPath);

                // Check again
                remainingKeys = keyValueClient.getKeys(configPath);
                if (remainingKeys != null && !remainingKeys.isEmpty()) {
                    LOG.error("Failed to delete keys after second attempt: {}", remainingKeys);
                    return false;
                }
            }
        } catch (Exception e) {
            LOG.error("Error verifying key deletion: {}", configPath, e);
            return false;
        }

        return result;
    }
}
