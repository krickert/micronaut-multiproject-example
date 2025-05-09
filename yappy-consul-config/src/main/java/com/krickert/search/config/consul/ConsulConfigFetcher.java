package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.registry.model.SchemaVersionData; // From your models module

import java.io.Closeable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fetches configuration data from Consul and manages watches for live updates.
 * Implementations will use a Consul client (e.g., kiwiproject).
 */
public interface ConsulConfigFetcher extends Closeable { // Closeable to manage Consul client/cache lifecycle

    /**
     * Initializes the connection to Consul.
     * Should be called before other methods.
     */
    void connect();

    /**
     * Fetches the PipelineClusterConfig for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return An Optional containing the deserialized PipelineClusterConfig if found and valid JSON,
     * otherwise empty.
     */
    Optional<PipelineClusterConfig> fetchPipelineClusterConfig(String clusterName);

    /**
     * Fetches a specific schema version's data from Consul.
     *
     * @param subject The subject of the schema artifact.
     * @param version The version of the schema.
     * @return An Optional containing the deserialized SchemaVersionData if found and valid JSON,
     * otherwise empty.
     */
    Optional<SchemaVersionData> fetchSchemaVersionData(String subject, int version);

    /**
     * Establishes a watch on the PipelineClusterConfig key for the given cluster name.
     * The updateHandler will be invoked when the configuration changes in Consul.
     * The handler receives the newly fetched configuration (if present after update)
     * or an empty Optional if the key was deleted.
     *
     * @param clusterName The name of the cluster whose config key to watch.
     * @param updateHandler A consumer that processes the new Optional<PipelineClusterConfig>.
     * It's the handler's responsibility to manage any old state if needed for diffing.
     */
    void watchClusterConfig(String clusterName, Consumer<Optional<PipelineClusterConfig>> updateHandler);

    /**
     * Stops any active watches and cleans up resources (e.g., stops KVCache, closes Consul client).
     * This method is idempotent.
     */
    @Override
    void close(); // From Closeable
}