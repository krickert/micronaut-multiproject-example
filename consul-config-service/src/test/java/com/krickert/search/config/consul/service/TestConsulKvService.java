package com.krickert.search.config.consul.service;

import org.kiwiproject.consul.KeyValueClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A simple implementation of ConsulKvService for testing purposes.
 * This implementation doesn't actually interact with Consul, but instead stores values in memory.
 */
public class TestConsulKvService extends ConsulKvService {
    private final Map<String, String> kvStore = new HashMap<>();
    private final String configPath;

    /**
     * Creates a new TestConsulKvService with the specified config path.
     *
     * @param configPath the base path for configuration
     */
    public TestConsulKvService(String configPath) {
        super(null, configPath);
        this.configPath = configPath;
    }

    /**
     * Gets a value from the in-memory KV store.
     *
     * @param key the key to get
     * @return a Mono containing an Optional with the value if found, or empty if not found
     */
    @Override
    public Mono<Optional<String>> getValue(String key) {
        return Mono.just(Optional.ofNullable(kvStore.get(key)));
    }

    /**
     * Puts a value into the in-memory KV store.
     *
     * @param key the key to put
     * @param value the value to put
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    @Override
    public Mono<Boolean> putValue(String key, String value) {
        kvStore.put(key, value);
        return Mono.just(true);
    }

    /**
     * Deletes a key from the in-memory KV store.
     *
     * @param key the key to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    @Override
    public Mono<Boolean> deleteKey(String key) {
        kvStore.remove(key);
        return Mono.just(true);
    }

    /**
     * Gets the full path for a key.
     *
     * @param key the key
     * @return the full path
     */
    @Override
    public String getFullPath(String key) {
        if (key.startsWith(configPath)) {
            return key;
        }
        return configPath + (configPath.endsWith("/") ? "" : "/") + key;
    }

    /**
     * Clears all values from the in-memory KV store.
     * This is useful for testing to reset the state between tests.
     */
    public void clear() {
        kvStore.clear();
    }

    /**
     * Puts multiple values into the in-memory KV store.
     *
     * @param keyValueMap a map of keys to values to put
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    @Override
    public Mono<Boolean> putValues(Map<String, String> keyValueMap) {
        keyValueMap.forEach(kvStore::put);
        return Mono.just(true);
    }

    /**
     * Deletes multiple keys from the in-memory KV store.
     *
     * @param keys a list of keys to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    @Override
    public Mono<Boolean> deleteKeys(List<String> keys) {
        keys.forEach(kvStore::remove);
        return Mono.just(true);
    }
}
