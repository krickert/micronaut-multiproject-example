package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.schema.model.SchemaVersionData;

import java.io.Closeable;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Fetches configuration data from Consul and manages watches for live updates.
 */
public interface ConsulConfigFetcher extends Closeable {

    void connect();

    Optional<PipelineClusterConfig> fetchPipelineClusterConfig(String clusterName);

    Optional<SchemaVersionData> fetchSchemaVersionData(String subject, int version);

    /**
     * Establishes a watch on the PipelineClusterConfig key for the given cluster name.
     * The updateHandler will be invoked when the configuration changes in Consul or an error occurs.
     *
     * @param clusterName   The name of the cluster whose config key to watch.
     * @param updateHandler A consumer that processes the {@link WatchCallbackResult}.
     */
    void watchClusterConfig(String clusterName, Consumer<WatchCallbackResult> updateHandler);

    @Override
    void close();
}