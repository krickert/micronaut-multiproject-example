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
 * @param nextSteps List of IDs of the next pipeline steps to execute on successful completion.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 * @param errorSteps List of IDs of the pipeline steps to execute if this step encounters an error.
 * Can be null (treated as empty). If provided, elements cannot be null/blank.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
        @JsonProperty("pipelineStepId") String pipelineStepId,
        @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
        @JsonProperty("customConfig") JsonConfigOptions customConfig,
        @JsonProperty("kafkaListenTopics") List<String> kafkaListenTopics,
        @JsonProperty("kafkaPublishTopics") List<KafkaPublishTopic> kafkaPublishTopics,
        @JsonProperty("grpcForwardTo") List<String> grpcForwardTo,
        @JsonProperty("nextSteps") List<String> nextSteps,         // Added
        @JsonProperty("errorSteps") List<String> errorSteps        // Added
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

        // Validate and normalize kafkaListenTopics
        if (kafkaListenTopics == null) {
            kafkaListenTopics = Collections.emptyList();
        } else {
            for (String topic : kafkaListenTopics) {
                if (topic == null || topic.isBlank()) {
                    throw new IllegalArgumentException("kafkaListenTopics cannot contain null or blank topics.");
                }
            }
            kafkaListenTopics = List.copyOf(kafkaListenTopics);
        }

        // Validate and normalize kafkaPublishTopics
        if (kafkaPublishTopics == null) {
            kafkaPublishTopics = Collections.emptyList();
        } else {
            // List.copyOf will throw NullPointerException if any element in kafkaPublishTopics is null.
            // KafkaPublishTopic record itself should validate its internal fields.
            kafkaPublishTopics = List.copyOf(kafkaPublishTopics);
        }

        // Validate and normalize grpcForwardTo
        if (grpcForwardTo == null) {
            grpcForwardTo = Collections.emptyList();
        } else {
            for (String service : grpcForwardTo) {
                if (service == null || service.isBlank()) {
                    throw new IllegalArgumentException("grpcForwardTo cannot contain null or blank service identifiers.");
                }
            }
            grpcForwardTo = List.copyOf(grpcForwardTo);
        }

        // Validate and normalize nextSteps (Added)
        if (nextSteps == null) {
            nextSteps = Collections.emptyList();
        } else {
            for (String stepId : nextSteps) {
                if (stepId == null || stepId.isBlank()) {
                    throw new IllegalArgumentException("nextSteps cannot contain null or blank step IDs.");
                }
            }
            nextSteps = List.copyOf(nextSteps);
        }

        // Validate and normalize errorSteps (Added)
        if (errorSteps == null) {
            errorSteps = Collections.emptyList();
        } else {
            for (String stepId : errorSteps) {
                if (stepId == null || stepId.isBlank()) {
                    throw new IllegalArgumentException("errorSteps cannot contain null or blank step IDs.");
                }
            }
            errorSteps = List.copyOf(errorSteps);
        }
    }
}