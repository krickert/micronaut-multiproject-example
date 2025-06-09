package com.krickert.search.config.consul.service;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import org.kiwiproject.consul.model.agent.FullService;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.catalog.CatalogService;
import org.kiwiproject.consul.model.health.HealthCheck;
import org.kiwiproject.consul.model.health.ServiceHealth;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business operations service for managing pipeline configurations, service registry,
 * and other business-level operations in a distributed system.
 * 
 * This interface provides reactive operations for:
 * - KV Store operations for configuration management
 * - Service registry and discovery
 * - Pipeline and module configuration management
 * - Health checks and monitoring
 */
public interface BusinessOperationsService {

    // --- KV Store Operations ---

    /**
     * Deletes a cluster configuration from the store.
     *
     * @param clusterName the name of the cluster
     * @return a Mono indicating success (true) or failure (false)
     */
    Mono<Boolean> deleteClusterConfiguration(String clusterName);

    /**
     * Deletes a specific schema version.
     *
     * @param subject the schema subject
     * @param version the schema version
     * @return a Mono indicating success (true) or failure (false)
     */
    Mono<Boolean> deleteSchemaVersion(String subject, int version);

    /**
     * Stores a value at the specified key.
     *
     * @param key the storage key
     * @param value the value to store (will be serialized to JSON if not already a string)
     * @return a Mono indicating success (true) or failure (false)
     */
    Mono<Boolean> putValue(String key, Object value);

    /**
     * Stores a cluster configuration.
     *
     * @param clusterName the name of the cluster
     * @param clusterConfig the cluster configuration object
     * @return a Mono indicating success (true) or failure (false)
     */
    Mono<Boolean> storeClusterConfiguration(String clusterName, Object clusterConfig);

    /**
     * Stores a schema version.
     *
     * @param subject the schema subject
     * @param version the schema version
     * @param schemaData the schema data object
     * @return a Mono indicating success (true) or failure (false)
     */
    Mono<Boolean> storeSchemaVersion(String subject, int version, Object schemaData);

    // --- Service Registry and Discovery Operations ---

    /**
     * Registers a service with the service registry.
     *
     * @param registration the service registration details
     * @return a Mono indicating completion
     */
    Mono<Void> registerService(Registration registration);

    /**
     * Deregisters a service from the service registry.
     *
     * @param serviceId the service ID to deregister
     * @return a Mono indicating completion
     */
    Mono<Void> deregisterService(String serviceId);

    /**
     * Lists all available services.
     *
     * @return a Mono emitting a map of service names to their tags
     */
    Mono<Map<String, List<String>>> listServices();

    /**
     * Gets all instances of a specific service.
     *
     * @param serviceName the name of the service
     * @return a Mono emitting a list of service instances
     */
    Mono<List<CatalogService>> getServiceInstances(String serviceName);

    /**
     * Gets all healthy instances of a specific service.
     *
     * @param serviceName the name of the service
     * @return a Mono emitting a list of healthy service instances
     */
    Mono<List<ServiceHealth>> getHealthyServiceInstances(String serviceName);

    /**
     * Checks if the backend service (e.g., Consul) is available.
     *
     * @return a Mono emitting true if available, false otherwise
     */
    Mono<Boolean> isConsulAvailable();

    // --- Pipeline and Module Configuration Operations ---

    /**
     * Retrieves the pipeline cluster configuration for a specific cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting an Optional containing the cluster config if found
     */
    Mono<Optional<PipelineClusterConfig>> getPipelineClusterConfig(String clusterName);

    /**
     * Retrieves the pipeline graph configuration for a specific cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting an Optional containing the graph config if found
     */
    Mono<Optional<PipelineGraphConfig>> getPipelineGraphConfig(String clusterName);

    /**
     * Retrieves the pipeline module map for a specific cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting an Optional containing the module map if found
     */
    Mono<Optional<PipelineModuleMap>> getPipelineModuleMap(String clusterName);

    /**
     * Gets the allowed Kafka topics for a specific cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting a set of allowed topic names
     */
    Mono<Set<String>> getAllowedKafkaTopics(String clusterName);

    /**
     * Gets the allowed gRPC services for a specific cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting a set of allowed service names
     */
    Mono<Set<String>> getAllowedGrpcServices(String clusterName);

    /**
     * Retrieves a specific pipeline configuration.
     *
     * @param clusterName the name of the cluster
     * @param pipelineName the name of the pipeline
     * @return a Mono emitting an Optional containing the pipeline config if found
     */
    Mono<Optional<PipelineConfig>> getSpecificPipelineConfig(String clusterName, String pipelineName);

    /**
     * Lists all pipeline names in a cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting a list of pipeline names
     */
    Mono<List<String>> listPipelineNames(String clusterName);

    /**
     * Retrieves a specific pipeline module configuration.
     *
     * @param clusterName the name of the cluster
     * @param implementationId the module implementation ID
     * @return a Mono emitting an Optional containing the module config if found
     */
    Mono<Optional<PipelineModuleConfiguration>> getSpecificPipelineModuleConfiguration(String clusterName, String implementationId);

    /**
     * Lists all available pipeline module implementations in a cluster.
     *
     * @param clusterName the name of the cluster
     * @return a Mono emitting a list of module configurations
     */
    Mono<List<PipelineModuleConfiguration>> listAvailablePipelineModuleImplementations(String clusterName);

    /**
     * Gets the service whitelist.
     *
     * @return a Mono emitting a list of whitelisted service names
     */
    Mono<List<String>> getServiceWhitelist();

    /**
     * Gets the topic whitelist.
     *
     * @return a Mono emitting a list of whitelisted topic names
     */
    Mono<List<String>> getTopicWhitelist();

    /**
     * Gets health checks for a specific service.
     *
     * @param serviceName the name of the service
     * @return a Mono emitting a list of health checks
     */
    Mono<List<HealthCheck>> getServiceChecks(String serviceName);

    /**
     * Gets detailed information about a specific service instance as known by the agent.
     *
     * @param serviceId the ID of the service instance
     * @return a Mono emitting an Optional of FullService
     */
    Mono<Optional<FullService>> getAgentServiceDetails(String serviceId);

    // --- Helper Methods ---

    /**
     * Gets the full key for a cluster configuration.
     *
     * @param clusterName the name of the cluster
     * @return the full key path
     */
    String getFullClusterKey(String clusterName);

    /**
     * Lists the names of available clusters.
     *
     * @return a Mono emitting a list of distinct cluster names
     */
    Mono<List<String>> listAvailableClusterNames();

    /**
     * Cleans up test resources including clusters, schemas, and services.
     *
     * @param clusterNames cluster names to delete
     * @param schemaSubjects schema subjects to delete
     * @param serviceIds service IDs to deregister
     * @return a Mono indicating completion
     */
    Mono<Void> cleanupTestResources(Iterable<String> clusterNames, Iterable<String> schemaSubjects, Iterable<String> serviceIds);
    
    // --- Cluster Management Operations ---
    
    /**
     * Creates a new cluster with the given name and optional metadata.
     * This is useful for test setup or cluster initialization.
     * 
     * @param clusterName the name of the cluster to create
     * @param metadata optional metadata to associate with the cluster
     * @return a Mono that completes with true if successful
     */
    Mono<Boolean> createCluster(String clusterName, Map<String, String> metadata);
    
    /**
     * Sets the active cluster ID for the current context.
     * This is used to specify which cluster configuration should be active.
     * 
     * @param clusterId the ID of the cluster to set as active
     * @return a Mono that completes with true if successful
     */
    Mono<Boolean> setActiveCluster(String clusterId);
    
    /**
     * Gets the currently active cluster ID.
     * 
     * @return a Mono emitting the active cluster ID, or empty if none is set
     */
    Mono<Optional<String>> getActiveCluster();
    
    /**
     * Stores metadata for a specific cluster.
     * 
     * @param clusterName the name of the cluster
     * @param metadata the metadata to store
     * @return a Mono that completes with true if successful
     */
    Mono<Boolean> storeClusterMetadata(String clusterName, Map<String, String> metadata);
    
    /**
     * Retrieves metadata for a specific cluster.
     * 
     * @param clusterName the name of the cluster
     * @return a Mono emitting the cluster metadata, or empty map if none exists
     */
    Mono<Map<String, String>> getClusterMetadata(String clusterName);
}