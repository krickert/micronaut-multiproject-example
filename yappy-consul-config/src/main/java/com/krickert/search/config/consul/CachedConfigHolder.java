package com.krickert.search.config.consul;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;

import java.util.Map;
import java.util.Optional;

/**
 * Holds the current, validated pipeline cluster configuration and cached schema contents.
 * Implementations must be thread-safe.
 */
public interface CachedConfigHolder {

    /**
     * Retrieves the currently active and validated PipelineClusterConfig.
     *
     * @return An Optional containing the current config if available, or empty if none loaded/valid.
     */
    Optional<PipelineClusterConfig> getCurrentConfig();

    /**
     * Retrieves the content of a specific schema version if it's cached.
     * Schemas are typically cached if they are referenced by the current PipelineClusterConfig.
     *
     * @param schemaRef The reference to the schema (subject and version).
     * @return An Optional containing the schema content string, or empty if not found or not cached.
     */
    Optional<String> getSchemaContent(SchemaReference schemaRef);

    /**
     * Updates the cached configuration with a new, validated PipelineClusterConfig
     * and its associated schema contents. This operation should be atomic.
     *
     * @param newConfig      The new, validated PipelineClusterConfig.
     * @param newSchemaCache A map of SchemaReferences to their schema content strings,
     *                       representing all schemas referenced by the newConfig.
     */
    void updateConfiguration(PipelineClusterConfig newConfig, Map<SchemaReference, String> newSchemaCache);

    /**
     * Clears the current configuration, potentially used if the configuration source
     * indicates a deletion or a persistent error state.
     */
    void clearConfiguration();
}