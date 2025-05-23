package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineGraphConfig;
import com.krickert.search.config.pipeline.model.PipelineModuleConfiguration;
import com.krickert.search.config.pipeline.model.PipelineModuleMap;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.util.CollectionUtils;
import jakarta.inject.Singleton;
import org.apache.commons.compress.utils.Lists;
import org.kiwiproject.consul.AgentClient;
import org.kiwiproject.consul.CatalogClient;
import org.kiwiproject.consul.HealthClient;
import org.kiwiproject.consul.StatusClient;
import org.kiwiproject.consul.model.ConsulResponse;
import org.kiwiproject.consul.model.agent.FullService;
import org.kiwiproject.consul.model.agent.Registration;
import org.kiwiproject.consul.model.catalog.CatalogService;
import org.kiwiproject.consul.model.health.HealthCheck;
import org.kiwiproject.consul.model.health.Service;
import org.kiwiproject.consul.model.health.ServiceHealth;
import org.kiwiproject.consul.option.Options;
import org.kiwiproject.consul.option.QueryOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Singleton
public class ConsulBusinessOperationsService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulBusinessOperationsService.class);
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final String clusterConfigKeyPrefix;
    private final String schemaVersionsKeyPrefix;
    private final String whitelistsKeyPrefix;

    private final AgentClient agentClient;
    private final CatalogClient catalogClient;
    private final HealthClient healthClient;
    private final StatusClient statusClient;

    public ConsulBusinessOperationsService(
            ConsulKvService consulKvService,
            ObjectMapper objectMapper,
            @Value("${app.config.consul.key-prefixes.pipeline-clusters}") String clusterConfigKeyPrefix,
            @Value("${app.config.consul.key-prefixes.schema-versions}") String schemaVersionsKeyPrefix,
            @Value("${app.config.consul.key-prefixes.whitelists:config/pipeline/whitelists}") String whitelistsKeyPrefix,
            AgentClient agentClient,
            CatalogClient catalogClient,
            HealthClient healthClient,
            StatusClient statusClient
    ) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
        this.clusterConfigKeyPrefix = clusterConfigKeyPrefix;
        this.schemaVersionsKeyPrefix = schemaVersionsKeyPrefix;
        this.whitelistsKeyPrefix = whitelistsKeyPrefix;
        this.agentClient = agentClient;
        this.catalogClient = catalogClient;
        this.healthClient = healthClient;
        this.statusClient = statusClient;

        LOG.info("ConsulBusinessOperationsService initialized with cluster config path: {}, schema versions path: {}, whitelists path: {}",
                clusterConfigKeyPrefix, schemaVersionsKeyPrefix, whitelistsKeyPrefix);
    }

    // --- KV Store Operations ---

    public Mono<Boolean> deleteClusterConfiguration(String clusterName) {
        validateClusterName(clusterName);
        String fullClusterKey = getFullClusterKey(clusterName);
        LOG.info("Deleting cluster configuration for cluster: {}, key: {}", clusterName, fullClusterKey);
        return consulKvService.deleteKey(fullClusterKey);
    }

    public Mono<Boolean> deleteSchemaVersion(String subject, int version) {
        validateSchemaSubject(subject);
        validateSchemaVersion(version);
        String fullSchemaKey = getFullSchemaKey(subject, version);
        LOG.info("Deleting schema version for subject: {}, version: {}, key: {}", subject, version, fullSchemaKey);
        return consulKvService.deleteKey(fullSchemaKey);
    }

    public Mono<Boolean> putValue(String key, Object value) {
        validateKey(key);
        Objects.requireNonNull(value, "Value cannot be null");

        try {
            String jsonValue;
            if (value instanceof String) {
                jsonValue = (String) value;
            } else {
                jsonValue = objectMapper.writeValueAsString(value);
            }
            LOG.info("Storing value at key: {}, value length: {}", key, jsonValue.length());
            return consulKvService.putValue(key, jsonValue);
        } catch (Exception e) {
            LOG.error("Failed to serialize or store value for key: {}", key, e);
            return Mono.error(e);
        }
    }

    public Mono<Boolean> storeClusterConfiguration(String clusterName, Object clusterConfig) {
        validateClusterName(clusterName);
        Objects.requireNonNull(clusterConfig, "Cluster configuration cannot be null");
        String fullClusterKey = getFullClusterKey(clusterName);
        LOG.info("Storing cluster configuration for cluster: {}, key: {}", clusterName, fullClusterKey);
        return putValue(fullClusterKey, clusterConfig);
    }

    public Mono<Boolean> storeSchemaVersion(String subject, int version, Object schemaData) {
        validateSchemaSubject(subject);
        validateSchemaVersion(version);
        Objects.requireNonNull(schemaData, "Schema data cannot be null");
        String fullSchemaKey = getFullSchemaKey(subject, version);
        LOG.info("Storing schema version for subject: {}, version: {}, key: {}", subject, version, fullSchemaKey);
        return putValue(fullSchemaKey, schemaData);
    }

    // --- Service Registry and Discovery Operations ---

    public Mono<Void> registerService(Registration registration) {
        Objects.requireNonNull(registration, "Registration details cannot be null");
        LOG.info("Registering service with Consul: id='{}', name='{}', address='{}', port={}",
                registration.getId(), registration.getName(), registration.getAddress().orElse("N/A"), registration.getPort().orElse(0));
        return Mono.fromRunnable(() -> agentClient.register(registration))
                .doOnSuccess(v -> LOG.info("Service '{}' registered successfully.", registration.getName()))
                .doOnError(e -> LOG.error("Failed to register service '{}'", registration.getName(), e))
                .then();
    }

    public Mono<Void> deregisterService(String serviceId) {
        validateKey(serviceId);
        LOG.info("Deregistering service with ID: {}", serviceId);
        return Mono.fromRunnable(() -> agentClient.deregister(serviceId))
                .doOnSuccess(v -> LOG.info("Service '{}' deregistered successfully.", serviceId))
                .doOnError(e -> LOG.error("Failed to deregister service '{}'", serviceId, e))
                .then();
    }

    public Mono<Map<String, List<String>>> listServices() {
        LOG.debug("Listing all services from Consul catalog.");
        return Mono.fromCallable(() -> {
            ConsulResponse<Map<String, List<String>>> response = catalogClient.getServices(Options.BLANK_QUERY_OPTIONS);
            if (response != null && response.getResponse() != null) {
                LOG.info("Found {} services in catalog.", response.getResponse().size());
                return response.getResponse();
            }
            LOG.warn("Received null response or empty service list from Consul catalog.");
            return Collections.<String, List<String>>emptyMap();
        }).onErrorResume(e -> {
            LOG.error("Failed to list services from Consul catalog", e);
            return Mono.just(Collections.<String, List<String>>emptyMap());
        });
    }

    public Mono<List<CatalogService>> getServiceInstances(String serviceName) {
        validateKey(serviceName);
        LOG.debug("Getting all instances for service: {}", serviceName);
        return Mono.fromCallable(() -> {
            ConsulResponse<List<CatalogService>> response = catalogClient.getService(serviceName, Options.BLANK_QUERY_OPTIONS);
            if (response != null && response.getResponse() != null) {
                LOG.info("Found {} instances for service '{}'.", response.getResponse().size(), serviceName);
                return response.getResponse();
            }
            LOG.warn("Received null response or empty instance list for service '{}'.", serviceName);
            return Collections.<CatalogService>emptyList();
        }).onErrorResume(e -> {
            LOG.error("Failed to get instances for service '{}'", serviceName, e);
            return Mono.just(Collections.<CatalogService>emptyList());
        });
    }

    public Mono<List<ServiceHealth>> getHealthyServiceInstances(String serviceName) {
        validateKey(serviceName);
        LOG.debug("Getting healthy instances for service: {}", serviceName);
        return Mono.fromCallable(() -> {
            ConsulResponse<List<ServiceHealth>> response = healthClient.getHealthyServiceInstances(serviceName, Options.BLANK_QUERY_OPTIONS);
            if (response != null && response.getResponse() != null) {
                LOG.info("Found {} healthy instances for service '{}'.", response.getResponse().size(), serviceName);
                return response.getResponse();
            }
            LOG.warn("Received null response or empty healthy instance list for service '{}'.", serviceName);
            return Collections.<ServiceHealth>emptyList();
        }).onErrorResume(e -> {
            LOG.error("Failed to get healthy instances for service '{}'", serviceName, e);
            return Mono.just(Collections.<ServiceHealth>emptyList());
        });
    }

    public Mono<Boolean> isConsulAvailable() {
        LOG.debug("Checking Consul availability by attempting to get leader.");
        return Mono.fromCallable(() -> {
                    try {
                        String leader = statusClient.getLeader();
                        boolean available = leader != null && !leader.trim().isEmpty();
                        LOG.debug("Consul leader status: {}. Available: {}", leader, available);
                        return available;
                    } catch (Exception e) {
                        LOG.warn("Consul not available or error checking leader status. Error: {}", e.getMessage());
                        return false;
                    }
                })
                .onErrorReturn(false);
    }

    // --- NEWLY ADDED/EXPANDED METHODS for Pipeline and Whitelist Configurations ---

    /**
     * Fetches the complete PipelineClusterConfig for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting an Optional of PipelineClusterConfig.
     */
// In ConsulBusinessOperationsService.java

    public Mono<Optional<PipelineClusterConfig>> getPipelineClusterConfig(String clusterName) {
        validateClusterName(clusterName);
        // Use the same key construction logic as for writing
        String keyForKvRead = getFullClusterKey(clusterName);

        LOG.debug("Fetching PipelineClusterConfig for cluster: '{}' from final key: {}", clusterName, keyForKvRead);

        return consulKvService.getValue(keyForKvRead) // Pass the correctly formed final key
                .flatMap(jsonStringOptional -> {
                    if (jsonStringOptional.isPresent()) {
                        String jsonString = jsonStringOptional.get();
                        try {
                            PipelineClusterConfig config = objectMapper.readValue(jsonString, PipelineClusterConfig.class);
                            LOG.info("Successfully deserialized PipelineClusterConfig for cluster: {}", clusterName);
                            return Mono.just(Optional.of(config));
                        } catch (IOException e) {
                            LOG.error("Failed to deserialize PipelineClusterConfig for cluster '{}'. JSON: {}", clusterName, jsonString.substring(0, Math.min(jsonString.length(), 500)), e);
                            return Mono.just(Optional.<PipelineClusterConfig>empty());
                        }
                    } else {
                        LOG.warn("PipelineClusterConfig not found in Consul for cluster: '{}' at key: {}", clusterName, keyForKvRead);
                        return Mono.just(Optional.<PipelineClusterConfig>empty());
                    }
                })
                .onErrorResume(e -> {
                    LOG.error("Error fetching PipelineClusterConfig for cluster: '{}' from key: {}", clusterName, keyForKvRead, e);
                    return Mono.just(Optional.<PipelineClusterConfig>empty());
                });
    }

    /**
     * Fetches the PipelineGraphConfig for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting an Optional of PipelineGraphConfig.
     */
    public Mono<Optional<PipelineGraphConfig>> getPipelineGraphConfig(String clusterName) {
        return getPipelineClusterConfig(clusterName)
                .map(clusterConfigOpt -> clusterConfigOpt.map(PipelineClusterConfig::pipelineGraphConfig));
    }

    /**
     * Fetches the PipelineModuleMap for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting an Optional of PipelineModuleMap.
     */
    public Mono<Optional<PipelineModuleMap>> getPipelineModuleMap(String clusterName) {
        return getPipelineClusterConfig(clusterName)
                .map(clusterConfigOpt -> clusterConfigOpt.map(PipelineClusterConfig::pipelineModuleMap));
    }

    /**
     * Fetches the set of allowed Kafka topics for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting a Set of allowed Kafka topics, or an empty set if not found/configured.
     */
    public Mono<Set<String>> getAllowedKafkaTopics(String clusterName) {
        return getPipelineClusterConfig(clusterName)
                .map(clusterConfigOpt -> clusterConfigOpt.map(PipelineClusterConfig::allowedKafkaTopics).orElse(Collections.emptySet()));
    }

    /**
     * Fetches the set of allowed gRPC services for a given cluster name.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting a Set of allowed gRPC services, or an empty set if not found/configured.
     */
    public Mono<Set<String>> getAllowedGrpcServices(String clusterName) {
        return getPipelineClusterConfig(clusterName)
                .map(clusterConfigOpt -> clusterConfigOpt.map(PipelineClusterConfig::allowedGrpcServices).orElse(Collections.emptySet()));
    }

    /**
     * Fetches a specific PipelineConfig from a cluster by its name.
     *
     * @param clusterName  The name of the cluster.
     * @param pipelineName The name of the pipeline.
     * @return A Mono emitting an Optional of PipelineConfig.
     */
    public Mono<Optional<PipelineConfig>> getSpecificPipelineConfig(String clusterName, String pipelineName) {
        return getPipelineGraphConfig(clusterName)
                .map(graphConfigOpt -> graphConfigOpt.flatMap(graph ->
                        Optional.ofNullable(graph.pipelines()).map(pipelines -> pipelines.get(pipelineName))
                ));
    }

    /**
     * Lists all pipeline names defined within a specific cluster.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting a List of pipeline names, or an empty list if none found.
     */
    public Mono<List<String>> listPipelineNames(String clusterName) {
        return getPipelineGraphConfig(clusterName)
                .map(graphConfigOpt -> graphConfigOpt
                        .map(PipelineGraphConfig::pipelines)
                        .map(Map::keySet)
                        .map(ArrayList::new) // Convert Set to List
                        .orElse(Lists.newArrayList()));
    }

    /**
     * Fetches a specific PipelineModuleConfiguration from a cluster by its implementation ID.
     *
     * @param clusterName      The name of the cluster.
     * @param implementationId The implementation ID of the module.
     * @return A Mono emitting an Optional of PipelineModuleConfiguration.
     */
    public Mono<Optional<PipelineModuleConfiguration>> getSpecificPipelineModuleConfiguration(String clusterName, String implementationId) {
        return getPipelineModuleMap(clusterName)
                .map(moduleMapOpt -> moduleMapOpt.flatMap(map ->
                        Optional.ofNullable(map.availableModules()).map(modules -> modules.get(implementationId))
                ));
    }

    /**
     * Lists all available PipelineModuleConfigurations for a specific cluster.
     *
     * @param clusterName The name of the cluster.
     * @return A Mono emitting a List of PipelineModuleConfiguration, or an empty list if none found.
     */
    public Mono<List<PipelineModuleConfiguration>> listAvailablePipelineModuleImplementations(String clusterName) {
        return getPipelineModuleMap(clusterName)
                .map(moduleMapOpt -> moduleMapOpt
                        .map(PipelineModuleMap::availableModules)
                        .map(Map::values)
                        .map(ArrayList::new) // Convert Collection to List
                        .orElse(Lists.newArrayList()));
    }

    /**
     * Gets the service whitelist from Consul.
     * Assumes the whitelist is stored as a JSON array of strings.
     *
     * @return A Mono emitting a list of whitelisted service identifiers.
     */
    public Mono<List<String>> getServiceWhitelist() {
        String serviceWhitelistKey = whitelistsKeyPrefix.endsWith("/") ? whitelistsKeyPrefix + "services" : whitelistsKeyPrefix + "/services";
        LOG.debug("Fetching service whitelist from key: {}", serviceWhitelistKey);
        return consulKvService.getValue(serviceWhitelistKey)
                .flatMap(optJson -> {
                    if (optJson.isPresent()) {
                        try {
                            List<String> whitelist = objectMapper.readValue(optJson.get(), new TypeReference<List<String>>() {});
                            return Mono.just(whitelist);
                        } catch (IOException e) {
                            LOG.error("Failed to deserialize service whitelist: {}", e.getMessage());
                            return Mono.just(Collections.emptyList());
                        }
                    }
                    LOG.warn("Service whitelist not found at key: {}", serviceWhitelistKey);
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Gets the topic whitelist from Consul.
     * Assumes the whitelist is stored as a JSON array of strings.
     *
     * @return A Mono emitting a list of whitelisted topic names.
     */
    public Mono<List<String>> getTopicWhitelist() {
        String topicWhitelistKey = whitelistsKeyPrefix.endsWith("/") ? whitelistsKeyPrefix + "topics" : whitelistsKeyPrefix + "/topics";
        LOG.debug("Fetching topic whitelist from key: {}", topicWhitelistKey);
        return consulKvService.getValue(topicWhitelistKey)
                .flatMap(optJson -> {
                    if (optJson.isPresent()) {
                        try {
                            List<String> whitelist = objectMapper.readValue(optJson.get(), new TypeReference<List<String>>() {});
                            return Mono.just(whitelist);
                        } catch (IOException e) {
                            LOG.error("Failed to deserialize topic whitelist: {}", e.getMessage());
                            return Mono.just(Collections.emptyList());
                        }
                    }
                    LOG.warn("Topic whitelist not found at key: {}", topicWhitelistKey);
                    return Mono.just(Collections.emptyList());
                });
    }

    /**
     * Gets all health checks for a specific service name.
     *
     * @param serviceName The name of the service.
     * @return A Mono emitting a list of HealthCheck instances.
     */
    public Mono<List<HealthCheck>> getServiceChecks(String serviceName) {
        validateKey(serviceName);
        LOG.debug("Getting all health checks for service: {}", serviceName);
        return Mono.fromCallable(() -> {
            ConsulResponse<List<HealthCheck>> response = healthClient.getServiceChecks(serviceName, Options.BLANK_QUERY_OPTIONS);
            if (response != null && response.getResponse() != null) {
                LOG.info("Found {} health checks for service '{}'.", response.getResponse().size(), serviceName);
                return response.getResponse();
            }
            LOG.warn("Received null response or empty health check list for service '{}'.", serviceName);
            return Collections.<HealthCheck>emptyList();
        }).onErrorResume(e -> {
            LOG.error("Failed to get health checks for service '{}'", serviceName, e);
            return Mono.just(Collections.<HealthCheck>emptyList());
        });
    }

    /**
     * Gets detailed information about a specific service instance as known by the agent.
     * Note: This method's ability to return FullService might be limited by kiwiproject-consul's AgentClient.
     * It's often more reliable to get service details via HealthClient or CatalogClient by service name and then filter.
     *
     * @param serviceId The ID of the service instance.
     * @return A Mono emitting an Optional of FullService.
     */
    public Mono<Optional<FullService>> getAgentServiceDetails(String serviceId) {
        validateKey(serviceId);
        LOG.debug("Attempting to get agent details for service instance ID: {}", serviceId);
        return Mono.fromCallable(() -> {
                    Map<String, org.kiwiproject.consul.model.health.Service> agentServices = agentClient.getServices(); // Corrected type from your code
                    if (agentServices.containsKey(serviceId)) {
                        LOG.info("Service ID '{}' is known to the agent. For full health details, query HealthClient.", serviceId);
                        return Optional.<FullService>empty(); // Return the Optional directly
                    }
                    LOG.warn("Service ID '{}' not found in agent's list of services.", serviceId);
                    return Optional.<FullService>empty(); // Return the Optional directly
                })
                .onErrorResume(e -> {
                    LOG.error("Failed to get agent details for service instance ID '{}'", serviceId, e);
                    // Explicitly state the type of the Mono being returned by Mono.just
                    Mono<Optional<FullService>> errorFallback = Mono.just(Optional.<FullService>empty());
                    return errorFallback;
                    // Or, more concisely, but sometimes less clear for the compiler:
                    // return Mono.<Optional<FullService>>just(Optional.empty());
                });
    }



    // --- Helper and Validation Methods ---

    private String getFullClusterKey(String clusterName) {
        String prefix = clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/";
        return prefix + clusterName;
    }

    private String getFullSchemaKey(String subject, int version) {
        String prefix = schemaVersionsKeyPrefix.endsWith("/") ? schemaVersionsKeyPrefix : schemaVersionsKeyPrefix + "/";
        return String.format("%s%s/%d", prefix, subject, version);
    }

    private void validateClusterName(String clusterName) {
        if (clusterName == null || clusterName.trim().isEmpty()) {
            throw new IllegalArgumentException("Cluster name cannot be null or blank");
        }
    }

    private void validateSchemaSubject(String subject) {
        if (subject == null || subject.trim().isEmpty()) {
            throw new IllegalArgumentException("Schema subject cannot be null or blank");
        }
    }

    private void validateSchemaVersion(int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("Schema version must be greater than 0");
        }
    }

    private void validateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }
    }

    public Mono<Void> cleanupTestResources(Iterable<String> clusterNames, Iterable<String> schemaSubjects, Iterable<String> serviceIds) {
        LOG.info("Cleaning up test resources: clusters, schemas, and services.");
        Mono<Void> deleteClustersMono = Mono.empty();
        if (clusterNames != null) {
            for (String clusterName : clusterNames) {
                deleteClustersMono = deleteClustersMono.then(deleteClusterConfiguration(clusterName).then());
            }
        }
        Mono<Void> deleteSchemasMono = Mono.empty();
        if (schemaSubjects != null) {
            for (String subject : schemaSubjects) {
                deleteSchemasMono = deleteSchemasMono.then(deleteSchemaVersion(subject, 1).then());
            }
        }
        Mono<Void> deregisterServicesMono = Mono.empty();
        if (serviceIds != null) {
            for (String serviceId : serviceIds) {
                deregisterServicesMono = deregisterServicesMono.then(deregisterService(serviceId).onErrorResume(e -> {
                    LOG.warn("Failed to deregister service '{}' during cleanup, continuing. Error: {}", serviceId, e.getMessage());
                    return Mono.empty();
                }));
            }
        }
        return Mono.when(deleteClustersMono, deleteSchemasMono, deregisterServicesMono)
                .doOnSuccess(v -> LOG.info("Test resources cleanup completed."))
                .doOnError(e -> LOG.error("Error during test resources cleanup.", e));
    }
}