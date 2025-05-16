package com.krickert.search.config.pipeline.model; // Assuming this is where JsonConfigOptions exists

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.Objects;

/**
 * Configuration for a single processing unit (a "step") within a pipeline.
 * This record is immutable and uses the new routing model.
 *
 * @param pipelineStepId Unique ID of this step instance within the pipeline.
 * @param pipelineImplementationId Links to PipelineModuleConfiguration, defining the step's logic.
 * @param customConfig Custom JSON configuration specific to this step instance.
 * @param nextSteps List of target pipelineStepIds to execute on successful completion.
 * @param errorSteps List of target pipelineStepIds to execute if this step encounters an error.
 * @param transportType The transport mechanism (KAFKA, GRPC, INTERNAL) for this step.
 * @param kafkaConfig Configuration for Kafka transport, used if transportType is KAFKA.
 * @param grpcConfig Configuration for gRPC transport, used if transportType is GRPC.
 * @param stepType The type of step (PIPELINE, INITIAL_PIPELINE, SINK), which affects validation rules and behavior.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
    @JsonProperty("pipelineStepId") String pipelineStepId,
    @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
    @JsonProperty("customConfig") JsonConfigOptions customConfig, // Assuming JsonConfigOptions is an existing record

    // Logical flow definition
    @JsonProperty("nextSteps") List<String> nextSteps,
    @JsonProperty("errorSteps") List<String> errorSteps,

    // Physical transport configuration
    @JsonProperty("transportType") TransportType transportType,
    @JsonProperty("kafkaConfig") KafkaTransportConfig kafkaConfig,
    @JsonProperty("grpcConfig") GrpcTransportConfig grpcConfig,

    // Step type (PIPELINE, INITIAL_PIPELINE, SINK)
    @JsonProperty("stepType") StepType stepType
) {
    @JsonCreator
    public PipelineStepConfig(
        @JsonProperty("pipelineStepId") String pipelineStepId,
        @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
        @JsonProperty("customConfig") JsonConfigOptions customConfig,
        @JsonProperty("nextSteps") List<String> nextSteps,
        @JsonProperty("errorSteps") List<String> errorSteps,
        @JsonProperty("transportType") TransportType transportType,
        @JsonProperty("kafkaConfig") KafkaTransportConfig kafkaConfig,
        @JsonProperty("grpcConfig") GrpcTransportConfig grpcConfig,
        @JsonProperty("stepType") StepType stepType
    ) {
        // --- Essential Validations ---
        if (pipelineStepId == null || pipelineStepId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineStepId cannot be null or blank.");
        }
        this.pipelineStepId = pipelineStepId;

        if (pipelineImplementationId == null || pipelineImplementationId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineImplementationId cannot be null or blank.");
        }
        this.pipelineImplementationId = pipelineImplementationId;

        if (transportType == null) {
            throw new IllegalArgumentException("PipelineStepConfig transportType cannot be null.");
        }
        this.transportType = transportType;

        // Default to PIPELINE if stepType is null
        this.stepType = (stepType == null) ? StepType.PIPELINE : stepType;

        // --- Conditional Validations for Transport Configs ---
        if (this.transportType == TransportType.KAFKA && kafkaConfig == null) {
            throw new IllegalArgumentException("KafkaTransportConfig must be provided when transportType is KAFKA.");
        }
        if (this.transportType != TransportType.KAFKA && kafkaConfig != null) {
            throw new IllegalArgumentException("KafkaTransportConfig should only be provided when transportType is KAFKA (found type: " + this.transportType + ").");
        }
        this.kafkaConfig = kafkaConfig;

        if (this.transportType == TransportType.GRPC && grpcConfig == null) {
            throw new IllegalArgumentException("GrpcTransportConfig must be provided when transportType is GRPC.");
        }
        if (this.transportType != TransportType.GRPC && grpcConfig != null) {
            throw new IllegalArgumentException("GrpcTransportConfig should only be provided when transportType is GRPC (found type: " + this.transportType + ").");
        }
        this.grpcConfig = grpcConfig;

        // --- Custom Config can be null ---
        this.customConfig = customConfig;

        // --- Validate List Contents (no null elements) ---
        if (nextSteps != null) {
            for (String step : nextSteps) {
                if (step == null) {
                    throw new IllegalArgumentException("nextSteps cannot contain null or blank step IDs: " + nextSteps);
                }
            }
        }

        if (errorSteps != null) {
            for (String step : errorSteps) {
                if (step == null) {
                    throw new IllegalArgumentException("errorSteps cannot contain null or blank step IDs: " + errorSteps);
                }
            }
        }

        // --- Defensive Copies for Lists ---
        this.nextSteps = (nextSteps == null) ? Collections.emptyList() : List.copyOf(nextSteps);
        this.errorSteps = (errorSteps == null) ? Collections.emptyList() : List.copyOf(errorSteps);

        // --- Validate for blank step IDs ---
        for (String stepId : this.nextSteps) {
            if (stepId.isBlank()) {
                throw new IllegalArgumentException("nextSteps cannot contain null or blank step IDs: " + nextSteps);
            }
        }

        for (String stepId : this.errorSteps) {
            if (stepId.isBlank()) {
                throw new IllegalArgumentException("errorSteps cannot contain null or blank step IDs: " + errorSteps);
            }
        }
    }
}
