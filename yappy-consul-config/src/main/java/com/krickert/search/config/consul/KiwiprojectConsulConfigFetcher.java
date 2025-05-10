package com.krickert.search.config.consul;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HostAndPort; // From Guava, often included with kiwiproject or add explicitly
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.registry.model.SchemaVersionData;

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

/**
 * A fetcher implementation that interacts with Consul to retrieve and optionally watch
 * configuration data such as pipeline cluster configurations and schema version data.
 * It uses the Consul Key/Value store as the source of configurations and deserializes
 * them into domain objects using Jackson's {@link ObjectMapper}.
 * <br/>
 * This class is configured to be used in applications that rely on Consul for managing
 * configuration data, such as distributed systems or microservice architectures. It can
 * also establish watches on specific keys to react to configuration changes in near
 * real-time.
 * <br/>
 * This implementation:
 * - Connects to a Consul server with optional ACL token authentication.
 * - Fetches pipeline cluster configurations based on predefined key prefixes.
 * - Fetches schema version data based on predefined key prefixes.
 * - Provides a mechanism to watch for changes to specific configuration keys
 *   and invoke a handler when changes are detected.
 * <br/>
 * The configuration values for Consul interaction (e.g., host, port, token, key prefixes,
 * and watch interval) are mostly configurable using application properties.
 * <br/>
 * An important note is that this class should only be instantiated if the `consul.enabled`
 * property is set to `true` (defaulted to `true` if not explicitly set), ensuring that
 * it is only used in environments where Consul is expected to be operational.
 * <br/>
 * Thread-safety considerations:
 * - The class uses synchronization to ensure thread-safety of connection establishment
 *   and state changes.
 * - AtomicBoolean is utilized to manage connection and watcher states.
 */
@Singleton
@Requires(property = "consul.enabled", value = "true", defaultValue = "true") // Only create bean if consul is enabled
public class KiwiprojectConsulConfigFetcher implements ConsulConfigFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(KiwiprojectConsulConfigFetcher.class);

    private final ObjectMapper objectMapper;
    private final String consulHost;
    private final int consulPort;
    private final Optional<String> consulToken; // Optional ACL token
    private final String clusterConfigKeyPrefix;
    private final String schemaVersionsKeyPrefix;
    private final int watchSeconds;

    private Consul consulClient;
    private KeyValueClient kvClient;
    private KVCache clusterConfigCache;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean watcherStarted = new AtomicBoolean(false);


    /**
     * Constructor for KiwiprojectConsulConfigFetcher.
     *
     * @param objectMapper The ObjectMapper used for JSON serialization and deserialization.
     * @param consulHost The hostname of the Consul service. Defaults to "localhost" if not specified.
     * @param consulPort The port of the Consul service. Defaults to 8500 if not specified.
     * @param consulTokenString The authentication token for Consul. If blank or null, no token is used.
     * @param clusterConfigKeyPrefix The key prefix used to retrieve pipeline cluster configurations from Consul.
     *                               Defaults to "pipeline-configs/clusters" if not specified.
     * @param schemaVersionsKeyPrefix The key prefix used to retrieve schema version data from Consul.
     *                                 Defaults to "pipeline-configs/schemas/versions" if not specified.
     * @param watchSeconds The interval, in seconds, for Consul watches. Defaults to 30 seconds if not specified.
     */
    @Inject
    public KiwiprojectConsulConfigFetcher(
            ObjectMapper objectMapper,
            @Value("${consul.host:localhost}") String consulHost,
            @Value("${consul.port:8500}") int consulPort,
            @Value("${consul.token:}") String consulTokenString, // Read as string, convert to Optional
            @Value("${app.config.consul.key-prefixes.pipeline-clusters:pipeline-configs/clusters}") String clusterConfigKeyPrefix,
            @Value("${app.config.consul.key-prefixes.schema-versions:pipeline-configs/schemas/versions}") String schemaVersionsKeyPrefix,
            @Value("${app.config.consul.watch-seconds:30}") int watchSeconds
    ) {
        this.objectMapper = objectMapper;
        this.consulHost = consulHost;
        this.consulPort = consulPort;
        this.consulToken = (consulTokenString == null || consulTokenString.isBlank()) ? Optional.empty() : Optional.of(consulTokenString);
        this.clusterConfigKeyPrefix = clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/";
        this.schemaVersionsKeyPrefix = schemaVersionsKeyPrefix.endsWith("/") ? schemaVersionsKeyPrefix : schemaVersionsKeyPrefix + "/";
        this.watchSeconds = watchSeconds;
        LOG.info("KiwiprojectConsulConfigFetcher configured for Consul at {}:{}, Watch seconds: {}", consulHost, consulPort, watchSeconds);
    }

    private String getClusterConfigKey(String clusterName) {
        return clusterConfigKeyPrefix + clusterName;
    }

    private String getSchemaVersionKey(String subject, int version) {
        return String.format("%s%s/%d", schemaVersionsKeyPrefix, subject, version);
    }

    @Override
    public synchronized void connect() {
        if (connected.get()) {
            LOG.debug("Already connected to Consul.");
            return;
        }
        try {
            Consul.Builder consulBuilder = Consul.builder().withHostAndPort(HostAndPort.fromParts(consulHost, consulPort));
            consulToken.ifPresent(consulBuilder::withAclToken);
            this.consulClient = consulBuilder.build();
            this.kvClient = consulClient.keyValueClient();
            connected.set(true);
            LOG.info("Successfully connected to Consul at {}:{}", consulHost, consulPort);
        } catch (Exception e) {
            // Let this exception propagate or handle as per application's startup policy
            LOG.error("Failed to connect to Consul at {}:{}: {}", consulHost, consulPort, e.getMessage(), e);
            throw new IllegalStateException("Failed to connect to Consul", e);
        }
    }

    private void ensureConnected() {
        if (!connected.get()) {
            LOG.warn("Consul client not connected. Attempting to connect now...");
            connect(); // Or throw if connect fails again
        }
    }

    @Override
    public Optional<PipelineClusterConfig> fetchPipelineClusterConfig(String clusterName) {
        ensureConnected();
        String key = getClusterConfigKey(clusterName);
        LOG.debug("Fetching PipelineClusterConfig from Consul key: {}", key);
        try {
            Optional<String> valueAsString = kvClient.getValueAsString(key);
            if (valueAsString.isPresent()) {
                return Optional.of(objectMapper.readValue(valueAsString.get(), PipelineClusterConfig.class));
            } else {
                LOG.warn("PipelineClusterConfig not found in Consul at key: {}", key);
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to deserialize PipelineClusterConfig from Consul key {}: {}", key, e.getMessage(), e);
        } catch (Exception e) {
            LOG.error("Error fetching PipelineClusterConfig from Consul key {}: {}", key, e.getMessage(), e);
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
            if (valueAsString.isPresent()) {
                return Optional.of(objectMapper.readValue(valueAsString.get(), SchemaVersionData.class));
            } else {
                LOG.warn("SchemaVersionData not found in Consul for subject '{}', version {} at key: {}", subject, version, key);
            }
        } catch (JsonProcessingException e) {
            LOG.error("Failed to deserialize SchemaVersionData for subject '{}', version {} from key {}: {}", subject, version, key, e.getMessage(), e);
        } catch (Exception e) {
            LOG.error("Error fetching SchemaVersionData for subject '{}', version {} from key {}: {}", subject, version, key, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @Override
    public synchronized void watchClusterConfig(String clusterName, Consumer<Optional<PipelineClusterConfig>> updateHandler) {
        ensureConnected();
        if (watcherStarted.get() && clusterConfigCache != null) {
            LOG.warn("Watcher for cluster '{}' already started. Ignoring duplicate request or re-establishing.", clusterName);
            // Optionally stop existing cache first if re-establishing is desired
            // this.close(); // This would stop the existing cache
        }

        String keyToWatch = getClusterConfigKey(clusterName);
        LOG.info("Establishing Consul KVCache watch for key: {} (watch interval: {}s)", keyToWatch, watchSeconds);

        try {
            // KVCache watches a "rootPath". If keyToWatch is the exact key, it should work.
            // The key within the map returned by KVCache listener might be empty string "" if rootPath is the exact key.
            clusterConfigCache = KVCache.newCache(kvClient, keyToWatch, watchSeconds);

            clusterConfigCache.addListener(newValues -> {
                LOG.debug("KVCache listener invoked for key '{}'. Snapshot size: {}", keyToWatch, newValues.size());
                // When watching a single key as the rootPath, the key in newValues corresponding to this rootPath
                // is often an empty string, or the last component of the key. Needs testing with kiwiproject client.
                // Let's assume it's the empty string for the root itself.
                Optional<org.kiwiproject.consul.model.kv.Value> consulApiValueOpt = Optional.ofNullable(newValues.get(keyToWatch)) // Try full key first
                                     .or(() -> Optional.ofNullable(newValues.get(""))); // Then try empty key

                if (consulApiValueOpt.isPresent()) {
                    Optional<String> valueAsString = consulApiValueOpt.get().getValueAsString();
                    if (valueAsString.isPresent() && !valueAsString.get().isBlank()) {
                        LOG.info("Configuration changed for key '{}', new value received.", keyToWatch);
                        try {
                            PipelineClusterConfig config = objectMapper.readValue(valueAsString.get(), PipelineClusterConfig.class);
                            updateHandler.accept(Optional.of(config));
                        } catch (JsonProcessingException e) {
                            LOG.error("Failed to deserialize updated PipelineClusterConfig from watch for key {}: {}", keyToWatch, e.getMessage(), e);
                            // Decide: call handler with empty, or just log and wait for next valid?
                            // For safety, perhaps don't push a bad deserialization. The handler expects a valid structure.
                        }
                    } else {
                        LOG.info("Configuration for key '{}' was deleted or value is blank. Notifying handler.", keyToWatch);
                        updateHandler.accept(Optional.empty()); // Key deleted or value blank
                    }
                } else {
                    // This case means the KVCache listener was invoked, but the specific key (or its empty string alias)
                    // was not in the snapshot from the cache, or its value was truly null (not just empty string).
                    // This typically means the key was deleted.
                    LOG.info("Watched key '{}' no longer present in KVCache snapshot (deleted). Notifying handler.", keyToWatch);
                    updateHandler.accept(Optional.empty());
                }
            });

            clusterConfigCache.start();
            watcherStarted.set(true);
            LOG.info("KVCache for key '{}' started successfully.", keyToWatch);
        } catch (Exception e) {
            watcherStarted.set(false); // Ensure flag is correct if start fails
            LOG.error("Failed to start KVCache for key {}: {}", keyToWatch, e.getMessage(), e);
            // Propagate or handle according to policy. This is a critical failure for live updates.
            // For now, we log. The DynamicConfigurationManager might retry later or fail.
            throw new RuntimeException("Failed to establish Consul watch on " + keyToWatch, e);
        }
    }

    @Override
    @PreDestroy // Micronaut lifecycle annotation for shutdown
    public synchronized void close() {
        LOG.info("Closing KiwiprojectConsulConfigFetcher...");
        if (clusterConfigCache != null && watcherStarted.get()) {
            try {
                clusterConfigCache.stop();
                LOG.info("KVCache stopped.");
            } catch (Exception e) {
                LOG.error("Error stopping KVCache: {}", e.getMessage(), e);
            }
        }
        // The kiwiproject Consul client doesn't have an explicit close() method.
        // Its underlying HTTP client might need cleanup if not managed by a shared pool.
        // For most use cases, letting it be garbage collected or the application terminating is sufficient.
        // If using a shared Consul client bean, it shouldn't be closed here.
        // If this class exclusively owns the client, consider if cleanup is needed.
        // For kiwiproject, typically no explicit close is required for the Consul object itself.
        watcherStarted.set(false);
        connected.set(false);
        clusterConfigCache = null;
        kvClient = null;
        consulClient = null;
        LOG.info("KiwiprojectConsulConfigFetcher closed.");
    }
}