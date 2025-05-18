package com.krickert.search.config.consul;

import com.krickert.search.config.consul.event.ClusterConfigUpdateEvent;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
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

    Optional<PipelineConfig> getPipelineConfig(String pipelineId);
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
     * Adds a new Kafka topic to the allowed topics in the configuration.
     *
     * @param newTopic The new Kafka topic to add.
     * @return True if the operation was successful, false otherwise.
     */
    boolean addKafkaTopic(String newTopic);

    /**
     * Updates a pipeline step to use a new Kafka topic.
     * Specifically, modifies the text-enrichment step to send to the new topic.
     *
     * @param pipelineName The name of the pipeline containing the step to update.
     * @param stepName The name of the step to update.
     * @param outputKey The key for the new output.
     * @param newTopic The new Kafka topic to use.
     * @param targetStepName The name of the target step.
     * @return True if the operation was successful, false otherwise.
     */
    boolean updatePipelineStepToUseKafkaTopic(String pipelineName, String stepName, String outputKey, String newTopic, String targetStepName);

    /**
     * Deletes a service and updates all connections to/from it.
     *
     * @param serviceName The name of the service to delete.
     * @return True if the operation was successful, false otherwise.
     */
    boolean deleteServiceAndUpdateConnections(String serviceName);

    /**
     * Shuts down the configuration manager, stopping watches and releasing resources.
     * This might be triggered by an application shutdown event.
     */
    void shutdown();
}
