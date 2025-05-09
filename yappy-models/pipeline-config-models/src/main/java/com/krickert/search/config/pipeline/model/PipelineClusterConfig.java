package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import java.util.Collections; // For unmodifiable set
// No Lombok needed

/**
 * The top-level configuration for a "cluster" of pipelines.
 * Contains the graph of all pipelines, a map of available module types,
 * and whitelists for Kafka topics and gRPC services.
 * This record is immutable.
 *
 * @param clusterName The name of the application cluster. Must not be null or blank.
 * @param pipelineGraphConfig The full pipeline graph configuration. Can be null.
 * @param pipelineModuleMap The mapping of available pipeline modules. Can be null.
 * @param allowedKafkaTopics A whitelist of Kafka topic names allowed within this cluster.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 * @param allowedGrpcServices A whitelist of gRPC service identifiers allowed within this cluster.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineClusterConfig(
    @JsonProperty("clusterName") String clusterName,
    @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
    @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
    @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics,
    @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices
) {
    // Canonical constructor with validation and making collections unmodifiable
    public PipelineClusterConfig {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("PipelineClusterConfig clusterName cannot be null or blank.");
        }
        // pipelineGraphConfig and pipelineModuleMap can be null

        allowedKafkaTopics = (allowedKafkaTopics == null) ? Collections.emptySet() : Set.copyOf(allowedKafkaTopics);
        allowedGrpcServices = (allowedGrpcServices == null) ? Collections.emptySet() : Set.copyOf(allowedGrpcServices);

        allowedKafkaTopics.forEach(topic -> {
            if (topic == null || topic.isBlank()) throw new IllegalArgumentException("allowedKafkaTopics cannot contain null or blank topics.");
        });
        allowedGrpcServices.forEach(service -> {
            if (service == null || service.isBlank()) throw new IllegalArgumentException("allowedGrpcServices cannot contain null or blank service identifiers.");
        });
    }

    /**
     * Convenience constructor for minimal valid configuration.
     * @param clusterName The name of the cluster.
     */
    public PipelineClusterConfig(String clusterName) {
        this(clusterName, new PipelineGraphConfig(Collections.emptyMap()), new PipelineModuleMap(Collections.emptyMap()), Collections.emptySet(), Collections.emptySet());
    }
}