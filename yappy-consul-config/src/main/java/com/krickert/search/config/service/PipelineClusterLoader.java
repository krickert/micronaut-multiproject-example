package com.krickert.search.config.service;

import com.krickert.search.config.model.PipelineClusterConfig;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;

/**
 * Service interface for loading pipeline cluster configurations into an application.
 * This service is responsible for loading, validating, and providing access to
 * pipeline cluster configurations.
 */
public interface PipelineClusterLoader {

    /**
     * Loads a pipeline cluster configuration by name.
     *
     * @param clusterName the name of the cluster to load
     * @return the loaded pipeline cluster configuration, or null if not found
     */
    @Nullable
    PipelineClusterConfig loadCluster(@NonNull String clusterName);

    /**
     * Gets the currently loaded pipeline cluster configuration.
     *
     * @return the current pipeline cluster configuration, or null if none is loaded
     */
    @Nullable
    PipelineClusterConfig getCurrentCluster();

    /**
     * Validates a pipeline cluster configuration.
     *
     * @param clusterConfig the configuration to validate
     * @return true if the configuration is valid, false otherwise
     */
    boolean validateClusterConfig(@NonNull PipelineClusterConfig clusterConfig);

    /**
     * Gets validation errors from the last validation attempt.
     *
     * @return a string containing validation errors, or null if there are no errors
     */
    @Nullable
    String getValidationErrors();
}