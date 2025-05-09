package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Pipeline Cluster is a set of services running in a network to isolate a set of pipelines.
 * This class represents the configuration for a pipeline cluster, including security whitelists.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineClusterConfig {
    /**
     * The name of a single application cluster, which will match what is in consul or other service discovery.
     */
    private String clusterName;

    /**
     * The full pipeline graph configuration.
     */
    private PipelineGraphConfig pipelineGraphConfig;

    /**
     * Represents the mapping of available pipeline modules within this pipeline cluster.
     * This map is keyed by the module implementation ID (unique service ID) and provides
     * the module's definition, including its custom configuration JSON schema.
     */
    private PipelineModuleMap pipelineModuleMap;

    /**
     * A whitelist of Kafka topic names that are allowed for listening or publishing
     * by any pipeline step within this cluster.
     * This is a security measure.
     */
    private Set<String> allowedKafkaTopics;

    /**
     * A whitelist of gRPC service identifiers (e.g., "package.Service/Method" or "serviceName")
     * that pipeline steps within this cluster are allowed to forward requests to.
     * This is a security measure.
     */
    private Set<String> allowedGrpcServices;
}