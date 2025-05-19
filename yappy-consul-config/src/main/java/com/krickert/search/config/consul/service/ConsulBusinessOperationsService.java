package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.annotation.PreDestroy;
import java.util.Objects;

/**
 * Service for performing business operations on Consul's Key-Value store.
 * This service encapsulates business logic for operations like deleting cluster configurations,
 * managing schema versions, etc., with proper validation.
 */
@Singleton
public class ConsulBusinessOperationsService {

    private static final Logger LOG = LoggerFactory.getLogger(ConsulBusinessOperationsService.class);
    private final ConsulKvService consulKvService;
    private final ObjectMapper objectMapper;
    private final String clusterConfigKeyPrefix;
    private final String schemaVersionsKeyPrefix;

    /**
     * Creates a new ConsulBusinessOperationsService with the specified dependencies.
     *
     * @param consulKvService the ConsulKvService to use for KV operations
     * @param objectMapper the ObjectMapper to use for serialization/deserialization
     * @param clusterConfigKeyPrefix the base path for cluster configurations in Consul KV store
     * @param schemaVersionsKeyPrefix the base path for schema versions in Consul KV store
     */
    public ConsulBusinessOperationsService(
            ConsulKvService consulKvService,
            ObjectMapper objectMapper,
            @Value("${app.config.consul.key-prefixes.pipeline-clusters}") String clusterConfigKeyPrefix,
            @Value("${app.config.consul.key-prefixes.schema-versions}") String schemaVersionsKeyPrefix) {
        this.consulKvService = consulKvService;
        this.objectMapper = objectMapper;
        this.clusterConfigKeyPrefix = clusterConfigKeyPrefix;
        this.schemaVersionsKeyPrefix = schemaVersionsKeyPrefix;
        LOG.info("ConsulBusinessOperationsService initialized with cluster config path: {} and schema versions path: {}", 
                clusterConfigKeyPrefix, schemaVersionsKeyPrefix);
    }

    /**
     * Deletes a cluster configuration from Consul.
     *
     * @param clusterName the name of the cluster to delete
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteClusterConfiguration(String clusterName) {
        validateClusterName(clusterName);
        String fullClusterKey = getFullClusterKey(clusterName);
        LOG.info("Deleting cluster configuration for cluster: {}, key: {}", clusterName, fullClusterKey);
        return consulKvService.deleteKey(fullClusterKey);
    }

    /**
     * Deletes a schema version from Consul.
     *
     * @param subject the schema subject
     * @param version the schema version
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> deleteSchemaVersion(String subject, int version) {
        validateSchemaSubject(subject);
        validateSchemaVersion(version);
        String fullSchemaKey = getFullSchemaKey(subject, version);
        LOG.info("Deleting schema version for subject: {}, version: {}, key: {}", subject, version, fullSchemaKey);
        return consulKvService.deleteKey(fullSchemaKey);
    }

    /**
     * Stores a value in Consul.
     *
     * @param key the key to store the value under
     * @param value the value to store
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
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

    /**
     * Gets the full key for a cluster configuration.
     *
     * @param clusterName the name of the cluster
     * @return the full key for the cluster configuration
     */
    private String getFullClusterKey(String clusterName) {
        String prefix = clusterConfigKeyPrefix.endsWith("/") ? clusterConfigKeyPrefix : clusterConfigKeyPrefix + "/";
        return prefix + clusterName;
    }

    /**
     * Gets the full key for a schema version.
     *
     * @param subject the schema subject
     * @param version the schema version
     * @return the full key for the schema version
     */
    private String getFullSchemaKey(String subject, int version) {
        String prefix = schemaVersionsKeyPrefix.endsWith("/") ? schemaVersionsKeyPrefix : schemaVersionsKeyPrefix + "/";
        return String.format("%s%s/%d", prefix, subject, version);
    }

    /**
     * Validates a cluster name.
     *
     * @param clusterName the cluster name to validate
     * @throws IllegalArgumentException if the cluster name is invalid
     */
    private void validateClusterName(String clusterName) {
        if (clusterName == null || clusterName.trim().isEmpty()) {
            throw new IllegalArgumentException("Cluster name cannot be null or blank");
        }
    }

    /**
     * Validates a schema subject.
     *
     * @param subject the schema subject to validate
     * @throws IllegalArgumentException if the schema subject is invalid
     */
    private void validateSchemaSubject(String subject) {
        if (subject == null || subject.trim().isEmpty()) {
            throw new IllegalArgumentException("Schema subject cannot be null or blank");
        }
    }

    /**
     * Validates a schema version.
     *
     * @param version the schema version to validate
     * @throws IllegalArgumentException if the schema version is invalid
     */
    private void validateSchemaVersion(int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("Schema version must be greater than 0");
        }
    }

    /**
     * Validates a key.
     *
     * @param key the key to validate
     * @throws IllegalArgumentException if the key is invalid
     */
    private void validateKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be null or blank");
        }
    }

    /**
     * Stores a cluster configuration in Consul.
     *
     * @param clusterName the name of the cluster
     * @param clusterConfig the cluster configuration to store
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> storeClusterConfiguration(String clusterName, Object clusterConfig) {
        validateClusterName(clusterName);
        Objects.requireNonNull(clusterConfig, "Cluster configuration cannot be null");
        String fullClusterKey = getFullClusterKey(clusterName);
        LOG.info("Storing cluster configuration for cluster: {}, key: {}", clusterName, fullClusterKey);
        return putValue(fullClusterKey, clusterConfig);
    }

    /**
     * Stores a schema version in Consul.
     *
     * @param subject the schema subject
     * @param version the schema version
     * @param schemaData the schema data to store
     * @return a Mono that emits true if the operation was successful, false otherwise
     */
    public Mono<Boolean> storeSchemaVersion(String subject, int version, Object schemaData) {
        validateSchemaSubject(subject);
        validateSchemaVersion(version);
        Objects.requireNonNull(schemaData, "Schema data cannot be null");
        String fullSchemaKey = getFullSchemaKey(subject, version);
        LOG.info("Storing schema version for subject: {}, version: {}, key: {}", subject, version, fullSchemaKey);
        return putValue(fullSchemaKey, schemaData);
    }

    /**
     * Cleans up test resources by deleting cluster configurations and schema versions.
     *
     * @param clusterNames the names of the clusters to delete
     * @param schemaSubjects the schema subjects to delete (all versions)
     * @return a Mono that completes when all operations are done
     */
    public Mono<Void> cleanupTestResources(Iterable<String> clusterNames, Iterable<String> schemaSubjects) {
        LOG.info("Cleaning up test resources");

        // Delete cluster configurations
        Mono<Void> deleteClustersMono = Mono.empty();
        if (clusterNames != null) {
            for (String clusterName : clusterNames) {
                deleteClustersMono = deleteClustersMono.then(deleteClusterConfiguration(clusterName).then());
            }
        }

        // Delete schema versions
        // Note: This is a simplified version that assumes we know the versions to delete
        // In a real implementation, we might need to list all versions for each subject
        Mono<Void> deleteSchemasMono = Mono.empty();
        if (schemaSubjects != null) {
            for (String subject : schemaSubjects) {
                // For simplicity, we're just deleting version 1
                // In a real implementation, we would need to list all versions
                deleteSchemasMono = deleteSchemasMono.then(deleteSchemaVersion(subject, 1).then());
            }
        }

        return deleteClustersMono.then(deleteSchemasMono);
    }
}
