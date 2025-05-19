// File: src/main/java/com/krickert/search/config/consul/KiwiprojectConsulConfigFetcher.java
package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.model.SchemaVersionData;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.kiwiproject.consul.cache.KVCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Singleton
@Requires(property = "consul.enabled", value = "true", defaultValue = "true")
public class KiwiprojectConsulConfigFetcher implements ConsulConfigFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(KiwiprojectConsulConfigFetcher.class);

    private final ObjectMapper objectMapper;
    private final String consulHostForInfo;
    private final int consulPortForInfo;
    final String clusterConfigKeyPrefix;
    final String schemaVersionsKeyPrefix;
    final int appWatchSeconds;

    // Package-private for test access
    Consul consulClient;
    KeyValueClient kvClient;
    KVCache clusterConfigCache;
    final AtomicBoolean connected = new AtomicBoolean(false);
    final AtomicBoolean watcherStarted = new AtomicBoolean(false);

    @Inject
    public KiwiprojectConsulConfigFetcher(
            ObjectMapper objectMapper,
            @Value("${consul.client.host}") String consulHost,
            @Value("${consul.client.port}") int consulPort,
            @Value("${app.config.consul.key-prefixes.pipeline-clusters}") String clusterConfigKeyPrefix,
            @Value("${app.config.consul.key-prefixes.schema-versions}") String schemaVersionsKeyPrefix,
            @Value("${app.config.consul.watch-seconds}") int appWatchSeconds,
            Consul consulClient // Injected from ConsulClientFactory
    ) {
        this.objectMapper = objectMapper;
        this.consulHostForInfo = consulHost;
        this.consulPortForInfo = consulPort;
        this.clusterConfigKeyPrefix = clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/";
        this.schemaVersionsKeyPrefix = schemaVersionsKeyPrefix.endsWith("/") ? schemaVersionsKeyPrefix : schemaVersionsKeyPrefix + "/";
        this.appWatchSeconds = appWatchSeconds;
        this.consulClient = consulClient;

        LOG.info("KiwiprojectConsulConfigFetcher configured for Consul (via injected client for host: {}, port: {}), App WatchSeconds: {}.",
                this.consulHostForInfo, this.consulPortForInfo, this.appWatchSeconds);
    }

    // Made package-private for unit testing
    String getClusterConfigKey(String clusterName) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("Cluster name cannot be null or blank for key construction.");
        }
        return clusterConfigKeyPrefix + clusterName;
    }

    // Made package-private for unit testing
    String getSchemaVersionKey(String subject, int version) {
        if (subject == null || subject.isBlank() || version < 1) {
            throw new IllegalArgumentException("Subject cannot be null/blank and version must be positive for schema key construction.");
        }
        return String.format("%s%s/%d", schemaVersionsKeyPrefix, subject, version);
    }

    @Override
    public synchronized void connect() {
        if (connected.get()) {
            LOG.debug("Consul client already confirmed as initialized and kvClient set.");
            return;
        }
        if (this.consulClient == null) {
            LOG.error("Injected Consul client is null. Cannot connect or fetch.");
            throw new IllegalStateException("Injected Consul client is null. Connection failed.");
        }
        try {
            this.kvClient = this.consulClient.keyValueClient();
            connected.set(true);
            LOG.info("Consul KeyValueClient obtained. Fetcher is considered connected.");
        } catch (Exception e) {
            connected.set(false);
            LOG.error("Failed to obtain KeyValueClient or confirm connection to Consul: {}", e.getMessage(), e);
            throw new IllegalStateException("Failed to initialize connection to Consul", e);
        }
    }

    private void ensureConnected() {
        if (!connected.get() || this.kvClient == null) {
            LOG.warn("Consul client not connected or kvClient not initialized. Attempting to connect/initialize now...");
            connect();
        }
    }

    @Override
    public Optional<PipelineClusterConfig> fetchPipelineClusterConfig(String clusterName) {
        ensureConnected();
        String key = getClusterConfigKey(clusterName);
        LOG.debug("Fetching PipelineClusterConfig from Consul key: {}", key);
        try {
            Optional<String> valueAsString = kvClient.getValueAsString(key);
            if (valueAsString.isPresent() && !valueAsString.get().isBlank()) {
                LOG.trace("Raw JSON for key {}: {}", key, valueAsString.get().length() > 200 ? valueAsString.get().substring(0,200) + "..." : valueAsString.get());
                return Optional.of(objectMapper.readValue(valueAsString.get(), PipelineClusterConfig.class));
            } else {
                LOG.warn("PipelineClusterConfig not found or value is blank in Consul at key: {}", key);
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to deserialize PipelineClusterConfig from Consul key '{}': {}", key, e.getMessage());
        } catch (Exception e) {
            LOG.error("Error fetching PipelineClusterConfig from Consul key '{}': {}", key, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public Optional<SchemaVersionData> fetchSchemaVersionData(String subject, int version) {
        ensureConnected();
        String key = getSchemaVersionKey(subject, version);
        LOG.debug("Fetching SchemaVersionData from Consul key: {}", key);
        try {
            Optional<String> valueAsString = kvClient.getValueAsString(key);
            if (valueAsString.isPresent() && !valueAsString.get().isBlank()) {
                LOG.trace("Raw JSON for key {}: {}", key, valueAsString.get().length() > 200 ? valueAsString.get().substring(0,200) + "..." : valueAsString.get());
                return Optional.of(objectMapper.readValue(valueAsString.get(), SchemaVersionData.class));
            } else {
                LOG.warn("SchemaVersionData not found or value is blank in Consul for subject '{}', version {} at key: {}", subject, version, key);
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to deserialize SchemaVersionData for subject '{}', version {} from key '{}': {}", subject, version, key, e.getMessage());
        } catch (Exception e) {
            LOG.error("Error fetching SchemaVersionData for subject '{}', version {} from key '{}': {}", subject, version, key, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void watchClusterConfig(String clusterName, Consumer<WatchCallbackResult> updateHandler) {
        ensureConnected();
        if (clusterConfigCache != null) {
            LOG.warn("KVCache for cluster '{}' (or a previous watch) already exists. Stopping existing before creating new.", clusterName);
            try {
                clusterConfigCache.stop();
            } catch (Exception e) {
                LOG.error("Error stopping existing KVCache for cluster '{}': {}", clusterName, e.getMessage(), e);
            }
            clusterConfigCache = null;
            watcherStarted.set(false);
        }

        String keyToWatch = getClusterConfigKey(clusterName);
        LOG.info("Establishing Consul KVCache watch for key: {} (app configured watch interval: {}s)",
                keyToWatch, this.appWatchSeconds);

        try {
            clusterConfigCache = KVCache.newCache(kvClient, keyToWatch, this.appWatchSeconds);

            clusterConfigCache.addListener(newValues -> { // newValues is Map<String, org.kiwiproject.consul.model.kv.Value>
                LOG.debug("KVCache listener for key '{}' received update. Raw newValues map: {}", keyToWatch, newValues);

                org.kiwiproject.consul.model.kv.Value consulApiValue = null;

                if (newValues.containsKey(keyToWatch)) {
                    // Ideal case: the map contains the key we are watching
                    consulApiValue = newValues.get(keyToWatch);
                    LOG.debug("Found value using exact keyToWatch: '{}'", keyToWatch);
                } else if (newValues.size() == 1) {
                    // If the map has only one entry, it's likely our watched key.
                    // Based on logs, the key in the map might be empty.
                    // The Value object's getKey() might also be unconventional for single key watches.
                    // For a single key watch, if KVCache gives us one entry, we assume it's the one.
                    Optional<org.kiwiproject.consul.model.kv.Value> firstValueOpt = newValues.values().stream().findFirst();
                    if (firstValueOpt.isPresent()) {
                        consulApiValue = firstValueOpt.get();
                        String internalKeyValue = consulApiValue.getKey(); // getKey() returns String

                        // Log what we found for diagnostics.
                        // We are now *assuming* this single entry IS for our keyToWatch,
                        // regardless of what its internal getKey() reports if it's not matching keyToWatch.
                        // This is a workaround for the KVCache behavior where the internal key might be the base64 value.
                        LOG.info("Found single value in map. Assuming it's for watched key '{}'. Map key was: '{}'. Value's internal key reports as: '{}'",
                                keyToWatch,
                                newValues.keySet().stream().findFirst().orElse("N/A"),
                                (internalKeyValue != null ? internalKeyValue : "null (Value.getKey() returned null)"));

                        // If the internal key *is* null or doesn't match, it's strange, but we might still
                        // proceed if we trust that a single-entry map for a single-key watch IS the value.
                        // However, if the internal key IS the base64 value, then `getValueAsString()` is what we need.
                        // The critical part is that `consulApiValue` is now set.
                    } else {
                        LOG.warn("KVCache newValues map had size 1 but contained no actual value. This is unexpected.");
                    }
                } else if (newValues.isEmpty()) {
                    LOG.debug("KVCache newValues map is empty.");
                    // consulApiValue remains null, will be treated as deleted.
                } else {
                    LOG.warn("KVCache newValues map has {} entries, but does not contain the watched key '{}'. Map keys: {}. This is unexpected for a single key watch.",
                            newValues.size(), keyToWatch, newValues.keySet());
                    // consulApiValue remains null, will be treated as deleted.
                }

                // The rest of your logic for processing consulApiValue:
                if (consulApiValue == null) {
                    LOG.info("Watched key '{}' effectively not present in KVCache snapshot. Treating as deleted.", keyToWatch);
                    updateHandler.accept(WatchCallbackResult.createAsDeleted());
                } else {
                    Optional<String> valueAsStringOpt = consulApiValue.getValueAsString(); // This should give the decoded JSON string

                    if (valueAsStringOpt.isPresent() && !valueAsStringOpt.get().isBlank()) {
                        String jsonValue = valueAsStringOpt.get();
                        LOG.info("Watched key '{}' present with non-blank value. Attempting deserialization. Length: {}", keyToWatch, jsonValue.length());
                        LOG.trace("Value for key '{}': {}", keyToWatch, jsonValue);
                        try {
                            PipelineClusterConfig config = objectMapper.readValue(jsonValue, PipelineClusterConfig.class);
                            updateHandler.accept(WatchCallbackResult.success(config));
                        } catch (JsonProcessingException e) {
                            LOG.error("Failed to deserialize updated PipelineClusterConfig from watch for key '{}': {}", keyToWatch, e.getMessage());
                            LOG.debug("Malformed JSON content from watch for key '{}': {}", keyToWatch, jsonValue, e);
                            updateHandler.accept(WatchCallbackResult.failure(e));
                        }
                    } else {
                        if (valueAsStringOpt.isPresent()) { // It was blank
                            LOG.info("Watched key '{}' IS PRESENT in KVCache snapshot but its value is blank. Treating as deleted/empty.", keyToWatch);
                        } else { // It was present in map but getValueAsString() was empty
                            LOG.info("Watched key '{}' IS PRESENT in KVCache snapshot but its value is null (Optional.empty from getValueAsString). Treating as deleted/empty.", keyToWatch);
                        }
                        updateHandler.accept(WatchCallbackResult.createAsDeleted());
                    }
                }
            });

            clusterConfigCache.start();
            watcherStarted.set(true);
            LOG.info("KVCache for key '{}' started successfully.", keyToWatch);
        } catch (Exception e) {
            watcherStarted.set(false);
            LOG.error("Failed to start KVCache for key {}: {}", keyToWatch, e.getMessage(), e);
            throw new RuntimeException("Failed to establish Consul watch on " + keyToWatch, e);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        LOG.info("Closing KiwiprojectConsulConfigFetcher...");
        if (clusterConfigCache != null) {
            try {
                clusterConfigCache.stop();
                LOG.info("KVCache stopped for cluster config watch.");
            } catch (Exception e) {
                LOG.error("Error stopping KVCache: {}", e.getMessage(), e);
            }
        }
        watcherStarted.set(false);
        clusterConfigCache = null;
        this.kvClient = null;       // Null out local reference for the derived client

        // DO NOT null out the injected consulClient if you want the ability to re-connect
        // for fetching operations after a close(). The factory/DI container manages the
        // lifecycle of the injected consulClient bean itself.
        // this.consulClient = null; // Comment this line out or remove it

        this.connected.set(false);
        LOG.info("KiwiprojectConsulConfigFetcher resources released and marked as disconnected.");
    }
}