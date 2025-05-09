package com.krickert.search.config.model;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Configuration for a pipeline step, which represents a single processing unit in a pipeline.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Introspected
@Serdeable
public class PipelineStepConfig {
    /**
     * The ID of the pipeline step (unique within a pipeline).
     */
    private String pipelineStepId;

    /**
     * The ID of the pipeline module implementation (references a key in PipelineModuleMap.availableModules).
     * This determines the type of service/logic to execute for this step and its configuration schema.
     */
    private String pipelineImplementationId;

    /**
     * Custom configuration options for this pipeline step, validated against the schema
     * defined in the corresponding PipelineModuleConfiguration.
     */
    private JsonConfigOptions customConfig; // Assumes JsonConfigOptions is properly initialized with schema

    /**
     * List of Kafka topic names this step should listen to for input.
     * These topics must be present in PipelineClusterConfig.allowedKafkaTopics.
     */
    private List<String> kafkaListenTopics;

    /**
     * List of Kafka topics this step will publish its output to.
     * These topics must be present in PipelineClusterConfig.allowedKafkaTopics.
     */
    private List<KafkaPublishTopic> kafkaPublishTopics; // Note: Class name changed to singular

    /**
     * List of gRPC service identifiers this step may forward requests or data to.
     * These services must be present in PipelineClusterConfig.allowedGrpcServices.
     * The format should be standardized (e.g., "package.ServiceName" or "servicename.methodname").
     */
    private List<String> grpcForwardTo;
}