package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of dynamic pipeline and schema configurations,
 * providing access to the current validated state and notifying listeners of updates.
 */
public interface DynamicConfigurationManager {

    /**
     * Initializes the configuration manager, performs the initial load, and starts watches.
     * This might be triggered by an application startup event in a Micronaut context.
     *
     * @param clusterName The name of the cluster this manager is responsible for.
     */
    void initialize(String clusterName);

    /**
     * Retrieves the currently active and validated PipelineClusterConfig.
     *
     * @return An Optional containing the current config, or empty if not yet loaded or invalid.
     */
    Optional<PipelineClusterConfig> getCurrentPipelineClusterConfig();

    /**
     * Retrieves the content of a specific schema version if it's actively referenced and cached.
     *
     * @param schemaRef The reference to the schema (subject and version).
     * @return An Optional containing the schema content string, or empty if not found.
     */
    Optional<String> getSchemaContent(SchemaReference schemaRef);

    /**
     * Registers a listener to be notified of validated cluster configuration updates.
     *
     * @param listener The consumer to be invoked with ClusterConfigUpdateEvent.
     */
    void registerConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener);

    /**
     * Unregisters a previously registered listener.
     *
     * @param listener The listener to remove.
     */
    void unregisterConfigUpdateListener(Consumer<ClusterConfigUpdateEvent> listener);


    /**
     * Shuts down the configuration manager, stopping watches and releasing resources.
     * This might be triggered by an application shutdown event.
     */
    void shutdown();
}