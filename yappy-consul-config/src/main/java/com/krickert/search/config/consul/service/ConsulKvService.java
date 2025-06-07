package com.krickert.search.config.consul.service;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.model.ConsulResponse;
import org.kiwiproject.consul.model.kv.ImmutableOperation;
import org.kiwiproject.consul.model.kv.Operation;
import org.kiwiproject.consul.model.kv.TxResponse;
import org.kiwiproject.consul.model.kv.Verb;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
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
@Requires(property = "consul.client.enabled", value = "true", defaultValue = "true")
public class ConsulKvService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulKvService.class);
    private final KeyValueClient keyValueClient;
    private final String configPath;

    /**
     * Creates a new ConsulKvService with the specified KeyValueClient.
     *
     * @param keyValueClient the KeyValueClient to use for KV operations
     * @param configPath     the base path for configuration in Consul KV store
     */
    public ConsulKvService(KeyValueClient keyValueClient,
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
                encodedKey.append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8)
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
                } else if (key.equals("config/pipeline/pipeline.seeded")) {
                    LOG.debug("No value found for key PIPELINE SEEDED, so returning EMPTY because WHY would it be here if it were " +
                            "anything else?: {}", key);
                    return Optional.empty();
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
     * @param key   the key to put
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
        LOG.debug("Putting multiple values using transaction: {}", keyValueMap.keySet());
        return Mono.fromCallable(() -> {
            try {
                // Create an array of operations for the transaction
                Operation[] operations = new Operation[keyValueMap.size()];
                int i = 0;
                List<String> keys = new ArrayList<>();

                for (Map.Entry<String, String> entry : keyValueMap.entrySet()) {
                    String encodedKey = encodeKey(entry.getKey());
                    keys.add(entry.getKey());

                    // Create a SET operation for each key-value pair
                    operations[i++] = ImmutableOperation.builder()
                            .verb(Verb.SET.toValue())
                            .key(encodedKey)
                            .value(entry.getValue())
                            .build();
                }

                // Perform the transaction
                ConsulResponse<TxResponse> response = keyValueClient.performTransaction(operations);

                // Check if the transaction was successful
                if (response != null && response.getResponse() != null &&
                        response.getResponse().errors() == null || response.getResponse().errors().isEmpty()) {
                    LOG.info("Successfully wrote all values to Consul KV using transaction: {}", keys);
                    return true;
                } else {
                    LOG.error("Failed to write values to Consul KV using transaction. Errors: {}",
                            response.getResponse() != null ?
                                    response.getResponse().errors() : "Unknown error");
                    return false;
                }
            } catch (Exception e) {
                LOG.error("Error writing multiple values to Consul KV using transaction: {}", keyValueMap.keySet(), e);
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
     * Deletes all keys with a given prefix from Consul KV store.
     *
     * @param prefix the prefix of the keys to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteKeysWithPrefix(String prefix) {
        LOG.debug("Deleting keys with prefix: {}", prefix);
        return Mono.fromCallable(() -> {
            try {
                String encodedPrefix = encodeKey(prefix);
                keyValueClient.deleteKeys(encodedPrefix);
                LOG.debug("Successfully deleted keys with prefix: {}", prefix);
                return true;
            } catch (Exception e) {
                LOG.error("Error deleting keys with prefix: {}", prefix, e);
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
     * Gets all keys with a given prefix from Consul KV store.
     *
     * @param prefix the prefix to search for
     * @return a Mono containing a List of keys with the given prefix
     */
    public Mono<List<String>> getKeysWithPrefix(String prefix) {
        LOG.debug("Getting keys with prefix: {}", prefix);
        return Mono.fromCallable(() -> {
            try {
                String encodedPrefix = encodeKey(prefix);
                List<String> keys = keyValueClient.getKeys(encodedPrefix);
                LOG.debug("Found {} keys with prefix: {}", keys.size(), prefix);
                return keys;
            } catch (Exception e) {
                LOG.error("Error getting keys with prefix: {}", prefix, e);
                return new ArrayList<>();
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

    /**
     * Resets Consul state by deleting all keys under the config path.
     * This is useful for ensuring a clean state between tests or during application reset.
     *
     * @param path the base path for configuration in Consul KV store
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> resetConsulState(String path) {
        LOG.info("Resetting Consul state by deleting all keys under: {}", path);

        // First attempt to delete keys
        return deleteKeysWithPrefix(path)
                .flatMap(result -> {
                    // Verify that keys were actually deleted
                    return getKeysWithPrefix(path)
                            .flatMap(remainingKeys -> {
                                if (remainingKeys != null && !remainingKeys.isEmpty()) {
                                    LOG.warn("Keys still exist after deletion attempt: {}", remainingKeys);
                                    // Try one more time with a small delay
                                    return Mono.delay(java.time.Duration.ofMillis(100))
                                            .then(Mono.fromCallable(() -> {
                                                try {
                                                    String encodedPath = encodeKey(path);
                                                    keyValueClient.deleteKeys(encodedPath);
                                                    return true;
                                                } catch (Exception e) {
                                                    LOG.error("Error in second attempt to delete keys: {}", path, e);
                                                    return false;
                                                }
                                            }))
                                            .flatMap(secondResult -> {
                                                // Check again
                                                return getKeysWithPrefix(path)
                                                        .map(keysAfterRetry -> {
                                                            if (keysAfterRetry != null && !keysAfterRetry.isEmpty()) {
                                                                LOG.error("Failed to delete keys after second attempt: {}", keysAfterRetry);
                                                                return false;
                                                            }
                                                            return secondResult;
                                                        });
                                            });
                                }
                                return Mono.just(result);
                            })
                            .onErrorResume(e -> {
                                LOG.error("Error verifying key deletion: {}", path, e);
                                return Mono.just(false);
                            });
                });
    }

    /**
     * Blocking version of resetConsulState.
     * Resets Consul state by deleting all keys under the config path.
     * This is useful for ensuring a clean state between tests or during application reset.
     *
     * @param path the base path for configuration in Consul KV store
     * @return true if the operation was successful, false otherwise
     */
    public boolean resetConsulStateBlocking(String path) {
        LOG.info("Resetting Consul state by deleting all keys under (blocking): {}", path);

        try {
            // First attempt to delete keys
            String encodedPath = encodeKey(path);
            keyValueClient.deleteKeys(encodedPath);

            // Verify that keys were actually deleted
            List<String> remainingKeys = keyValueClient.getKeys(encodedPath);
            if (remainingKeys != null && !remainingKeys.isEmpty()) {
                LOG.warn("Keys still exist after deletion attempt: {}", remainingKeys);
                // Try one more time with a small delay
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                keyValueClient.deleteKeys(encodedPath);

                // Check again
                remainingKeys = keyValueClient.getKeys(encodedPath);
                if (remainingKeys != null && !remainingKeys.isEmpty()) {
                    LOG.error("Failed to delete keys after second attempt: {}", remainingKeys);
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            LOG.error("Error resetting Consul state: {}", path, e);
            return false;
        }
    }

    /**
     * Gets the ModifyIndex of a key in Consul KV store.
     * This is useful for Compare-And-Swap (CAS) operations.
     *
     * @param key the key to get the ModifyIndex for
     * @return a Mono containing the ModifyIndex of the key, or 0 if the key doesn't exist
     */
    public Mono<Long> getModifyIndex(String key) {
        LOG.debug("Getting ModifyIndex for key: {}", key);
        return Mono.fromCallable(() -> {
            try {
                String encodedKey = encodeKey(key);

                // Use consistent read to ensure we get the latest index
                org.kiwiproject.consul.option.QueryOptions consistentQueryOptions =
                        org.kiwiproject.consul.option.ImmutableQueryOptions.builder()
                                .consistencyMode(org.kiwiproject.consul.option.ConsistencyMode.CONSISTENT)
                                .build();

                Optional<org.kiwiproject.consul.model.kv.Value> valueOpt =
                        keyValueClient.getValue(encodedKey, consistentQueryOptions);

                if (valueOpt.isPresent()) {
                    long modifyIndex = valueOpt.get().getModifyIndex();
                    LOG.debug("ModifyIndex for key '{}': {}", key, modifyIndex);
                    return modifyIndex;
                } else {
                    LOG.debug("Key '{}' not found, returning ModifyIndex 0", key);
                    return 0L;
                }
            } catch (Exception e) {
                LOG.error("Error getting ModifyIndex for key: {}", key, e);
                return 0L;
            }
        });
    }

    /**
     * Atomically updates pipeline metadata (version, lastUpdated) using a Check-and-Set
     * operation based on the expected ModifyIndex of the version key.
     * Optionally updates other pipeline keys within the same transaction.
     * <br/>
     * For new pipelines (where expectedVersionModifyIndex is 0), this method will use a simple
     * transaction without a CHECK_INDEX operation.
     * <br/>
     *
     * @param pipelineName               The name of the pipeline.
     * @param newVersion                 The new version number to set.
     * @param newLastUpdated             The new timestamp to set.
     * @param expectedVersionModifyIndex The ModifyIndex of the version key read just before attempting this update.
     *                                   The transaction will fail if the index has changed. Use 0 for new pipelines.
     * @param otherKeysToSet             An optional map of other full key paths -> values to set atomically. Can be null or empty.
     * @return A Mono emitting true if the CAS transaction succeeded, false otherwise (e.g., CAS check failed or other error).
     */
    public Mono<Boolean> savePipelineUpdateWithCas(
            String pipelineName,
            long newVersion,
            LocalDateTime newLastUpdated,
            long expectedVersionModifyIndex,
            @jakarta.annotation.Nullable Map<String, String> otherKeysToSet) {

        String versionKey = "pipeline.configs." + pipelineName + ".version";
        String lastUpdatedKey = "pipeline.configs." + pipelineName + ".lastUpdated";
        String versionKeyFullPath = getFullPath(versionKey);
        String lastUpdatedKeyFullPath = getFullPath(lastUpdatedKey);

        LOG.debug("Attempting CAS update for pipeline '{}'. Expected index for key '{}': {}",
                pipelineName, versionKeyFullPath, expectedVersionModifyIndex);

        return Mono.fromCallable(() -> {
            try {
                // --- Build Operations ---
                List<Operation> operationList = new ArrayList<>();

                // For new pipelines (expectedVersionModifyIndex == 0), skip the CHECK_INDEX operation
                boolean isNewPipeline = expectedVersionModifyIndex == 0;

                if (!isNewPipeline) {
                    // 1. CHECK_INDEX operation (must be first for CAS) - only for existing pipelines
                    Operation checkOperation = ImmutableOperation.builder()
                            .verb(Verb.CHECK_INDEX.toValue())
                            .key(encodeKey(versionKeyFullPath))
                            .index(BigInteger.valueOf(expectedVersionModifyIndex))
                            .build();
                    operationList.add(checkOperation);
                } else {
                    LOG.debug("Creating new pipeline '{}', skipping CHECK_INDEX operation", pipelineName);
                }

                // 2. SET new version
                Operation setVersionOperation = ImmutableOperation.builder()
                        .verb(Verb.SET.toValue())
                        .key(encodeKey(versionKeyFullPath))
                        .value(String.valueOf(newVersion))
                        .build();
                operationList.add(setVersionOperation);

                // 3. SET new lastUpdated timestamp
                Operation setLastUpdatedOperation = ImmutableOperation.builder()
                        .verb(Verb.SET.toValue())
                        .key(encodeKey(lastUpdatedKeyFullPath))
                        .value(newLastUpdated.toString()) // Convert LocalDateTime to String
                        .build();
                operationList.add(setLastUpdatedOperation);

                // 4. SET other keys if provided
                List<String> otherKeysLog = new ArrayList<>();
                if (otherKeysToSet != null && !otherKeysToSet.isEmpty()) {
                    for (Map.Entry<String, String> entry : otherKeysToSet.entrySet()) {
                        // Assuming keys in the map are already full paths
                        String encodedKey = encodeKey(entry.getKey());
                        operationList.add(ImmutableOperation.builder()
                                .verb(Verb.SET.toValue())
                                .key(encodedKey)
                                .value(entry.getValue())
                                .build());
                        otherKeysLog.add(entry.getKey()); // Log the original key
                    }
                    LOG.debug("Adding {} other keys to {} transaction: {}",
                            otherKeysToSet.size(),
                            isNewPipeline ? "new pipeline" : "CAS",
                            otherKeysLog);
                }

                Operation[] operations = operationList.toArray(new Operation[0]);

                // --- Perform Transaction ---
                LOG.debug("Executing {} transaction with {} operations for pipeline '{}'",
                        isNewPipeline ? "new pipeline" : "CAS",
                        operations.length,
                        pipelineName);
                ConsulResponse<TxResponse> response = keyValueClient.performTransaction(operations);

                // --- Check Result ---
                // Transaction succeeds if response exists and errors list is null or empty
                boolean success = response != null && response.getResponse() != null &&
                        (response.getResponse().errors() == null || response.getResponse().errors().isEmpty());

                if (success) {
                    LOG.info("Successfully {} pipeline via transaction for: {}",
                            isNewPipeline ? "created" : "updated",
                            pipelineName);
                    return true;
                } else {
                    // Log specific errors if available
                    String errors = (response != null && response.getResponse() != null && response.getResponse().errors() != null)
                            ? response.getResponse().errors().toString() : "Unknown reason";

                    if (!isNewPipeline && errors.contains("invalid index") ||
                            (response != null && response.getResponse() != null &&
                                    response.getResponse().errors() != null &&
                                    !response.getResponse().errors().isEmpty() &&
                                    response.getResponse().errors().getFirst().opIndex().get().intValue() == 0)) {
                        // This is a CAS check failure for an existing pipeline
                        LOG.warn("CAS check failed for pipeline update '{}'. Expected index: {}. Errors: {}",
                                pipelineName, expectedVersionModifyIndex, errors);
                    } else {
                        // Other transaction failure
                        LOG.error("Consul transaction failed for pipeline {}. Errors: {}",
                                isNewPipeline ? "creation" : "update",
                                errors);
                    }
                    return false; // Transaction failed
                }
            } catch (Exception e) {
                LOG.error("Error performing {} transaction for pipeline: {}",
                        expectedVersionModifyIndex == 0 ? "new pipeline" : "CAS update",
                        pipelineName, e);
                return false; // General error during transaction attempt
            }
        });
    }

    /**
     * Gets the version of a pipeline from Consul KV store.
     * This is useful for verifying pipeline state directly in Consul.
     *
     * @param pipelineName the name of the pipeline
     * @return a Mono containing an Optional with the version if found, or empty if not found
     */
    public Mono<Optional<String>> getPipelineVersion(String pipelineName) {
        String versionKey = getFullPath("pipeline.configs." + pipelineName + ".version");
        LOG.debug("Getting pipeline version for pipeline '{}' from key: {}", pipelineName, versionKey);
        return getValue(versionKey)
                .doOnSuccess(versionOpt -> {
                    if (versionOpt.isPresent()) {
                        LOG.debug("Found version for pipeline '{}': {}", pipelineName, versionOpt.get());
                    } else {
                        LOG.debug("No version found for pipeline '{}'", pipelineName);
                    }
                })
                .onErrorResume(e -> {
                    LOG.error("Error getting version for pipeline '{}': {}", pipelineName, e.getMessage(), e);
                    return Mono.just(Optional.empty());
                });
    }

    /**
     * Ensures that all keys with a given prefix are deleted from Consul KV store.
     * This method will attempt to delete the keys, verify the deletion, and retry if necessary.
     * It's particularly useful for test setup and teardown.
     *
     * @param prefix the prefix of the keys to delete
     * @return a Mono that emits true if all keys were successfully deleted, false otherwise
     */
    public Mono<Boolean> ensureKeysDeleted(String prefix) {
        LOG.debug("Ensuring all keys with prefix are deleted: {}", prefix);
        return deleteKeysWithPrefix(prefix)
                .flatMap(result -> {
                    // Verify that keys were actually deleted
                    return getKeysWithPrefix(prefix)
                            .flatMap(remainingKeys -> {
                                if (remainingKeys != null && !remainingKeys.isEmpty()) {
                                    LOG.debug("Keys still exist after initial cleanup: {}", remainingKeys);
                                    // Try one more time
                                    return deleteKeysWithPrefix(prefix)
                                            .flatMap(secondResult -> {
                                                // Verify again
                                                return getKeysWithPrefix(prefix)
                                                        .map(keysAfterRetry -> {
                                                            if (keysAfterRetry != null && !keysAfterRetry.isEmpty()) {
                                                                LOG.debug("Keys still exist after second cleanup attempt: {}", keysAfterRetry);
                                                                return false;
                                                            }
                                                            LOG.debug("Successfully deleted all keys with prefix: {}", prefix);
                                                            return true;
                                                        });
                                            });
                                }
                                LOG.debug("Verified no keys exist with prefix: {}", prefix);
                                return Mono.just(true);
                            })
                            .onErrorResume(e -> {
                                LOG.error("Error verifying key deletion: {}", prefix, e);
                                return Mono.just(false);
                            });
                });
    }

    @PreDestroy
    public void destroyAll() {
        LOG.info("Destroy called for Counsul KV service, just a tag and nothing to destroy");
    }
}
