package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Collections; // For unmodifiable lists

// No Lombok needed

/**
 * Configuration for a single processing unit (a "step") within a pipeline.
 * This record is immutable.
 *
 * @param pipelineStepId The ID of the pipeline step (unique within a pipeline). Must not be null or blank.
 * @param pipelineImplementationId The ID of the pipeline module implementation (references a key in
 * PipelineModuleMap.availableModules). Must not be null or blank.
 * @param customConfig Custom configuration options for this pipeline step. Can be null.
 * @param kafkaListenTopics List of Kafka topic names this step should listen to for input.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 * @param kafkaPublishTopics List of Kafka topics this step will publish its output to.
 * Can be null (treated as empty). If provided, elements cannot be null.
 * @param grpcForwardTo List of gRPC service identifiers this step may forward requests or data to.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
    @JsonProperty("pipelineStepId") String pipelineStepId,
    @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
    @JsonProperty("customConfig") JsonConfigOptions customConfig,
    @JsonProperty("kafkaListenTopics") List<String> kafkaListenTopics,
    @JsonProperty("kafkaPublishTopics") List<KafkaPublishTopic> kafkaPublishTopics,
    @JsonProperty("grpcForwardTo") List<String> grpcForwardTo
) {
    // Canonical constructor making collections unmodifiable and handling nulls
    public PipelineStepConfig {
        if (pipelineStepId == null || pipelineStepId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineStepId cannot be null or blank.");
        }
        if (pipelineImplementationId == null || pipelineImplementationId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineImplementationId cannot be null or blank.");
        }
        // customConfig can be null

        kafkaListenTopics = (kafkaListenTopics == null) ? Collections.emptyList() : List.copyOf(kafkaListenTopics);
        kafkaPublishTopics = (kafkaPublishTopics == null) ? Collections.emptyList() : List.copyOf(kafkaPublishTopics);
        grpcForwardTo = (grpcForwardTo == null) ? Collections.emptyList() : List.copyOf(grpcForwardTo);

        // Add validation for elements within lists if necessary
        kafkaListenTopics.forEach(topic -> {
            if (topic == null || topic.isBlank()) throw new IllegalArgumentException("kafkaListenTopics cannot contain null or blank topics.");
        });
        // KafkaPublishTopic record already validates its 'topic' field.
        // No need to check kafkaPublishTopics elements for null as List.copyOf throws NPE for null elements.
        grpcForwardTo.forEach(service -> {
            if (service == null || service.isBlank()) throw new IllegalArgumentException("grpcForwardTo cannot contain null or blank service identifiers.");
        });
    }
}