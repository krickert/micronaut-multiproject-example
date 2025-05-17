// File: yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/model/PipelineStepConfig.java
package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
    @JsonProperty("stepName") @NotBlank String stepName,
    @JsonProperty("stepType") @NotNull StepType stepType,
    @JsonProperty("description") String description,
    @JsonProperty("customConfigSchemaId") String customConfigSchemaId,
    @JsonProperty("customConfig") @Valid JsonConfigOptions customConfig,
    @JsonProperty("outputs") @Valid Map<String, OutputTarget> outputs,
    @JsonProperty("maxRetries") Integer maxRetries,
    @JsonProperty("retryBackoffMs") Long retryBackoffMs,
    @JsonProperty("maxRetryBackoffMs") Long maxRetryBackoffMs,
    @JsonProperty("retryBackoffMultiplier") Double retryBackoffMultiplier,
    @JsonProperty("stepTimeoutMs") Long stepTimeoutMs,
    @JsonProperty("processorInfo") @NotNull(message = "Processor information (processorInfo) must be provided.") @Valid ProcessorInfo processorInfo
) {

    // Canonical constructor is implicit. Add custom one for defaults/validation if needed.
    @JsonCreator // Help Jackson pick the right constructor if there are multiple or for specific needs
    public PipelineStepConfig(
        @JsonProperty("stepName") String stepName,
        @JsonProperty("stepType") StepType stepType,
        @JsonProperty("description") String description,
        @JsonProperty("customConfigSchemaId") String customConfigSchemaId,
        @JsonProperty("customConfig") JsonConfigOptions customConfig,
        @JsonProperty("outputs") Map<String, OutputTarget> outputs,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("retryBackoffMs") Long retryBackoffMs,
        @JsonProperty("maxRetryBackoffMs") Long maxRetryBackoffMs,
        @JsonProperty("retryBackoffMultiplier") Double retryBackoffMultiplier,
        @JsonProperty("stepTimeoutMs") Long stepTimeoutMs,
        @JsonProperty("processorInfo") ProcessorInfo processorInfo
    ) {
        this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");
        this.stepType = Objects.requireNonNull(stepType, "stepType cannot be null");
        this.description = description;
        this.customConfigSchemaId = customConfigSchemaId;
        this.customConfig = customConfig;
        this.outputs = (outputs == null) ? Collections.emptyMap() : Map.copyOf(outputs); // Defensive copy
        this.maxRetries = (maxRetries == null) ? 0 : maxRetries; // Defaulting
        this.retryBackoffMs = (retryBackoffMs == null) ? 1000L : retryBackoffMs; // Defaulting
        this.maxRetryBackoffMs = (maxRetryBackoffMs == null) ? 30000L : maxRetryBackoffMs; // Defaulting
        this.retryBackoffMultiplier = (retryBackoffMultiplier == null) ? 2.0 : retryBackoffMultiplier; // Defaulting
        this.stepTimeoutMs = stepTimeoutMs;
        this.processorInfo = Objects.requireNonNull(processorInfo, "processorInfo cannot be null");

        // Additional validation from old record (if still applicable for ProcessorInfo)
        if (this.processorInfo.grpcServiceName() != null && !this.processorInfo.grpcServiceName().isBlank() &&
            this.processorInfo.internalProcessorBeanName() != null && !this.processorInfo.internalProcessorBeanName().isBlank()) {
            throw new IllegalArgumentException("ProcessorInfo cannot have both grpcServiceName and internalProcessorBeanName set.");
        }
        if ((this.processorInfo.grpcServiceName() == null || this.processorInfo.grpcServiceName().isBlank()) &&
            (this.processorInfo.internalProcessorBeanName() == null || this.processorInfo.internalProcessorBeanName().isBlank())) {
            throw new IllegalArgumentException("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName set.");
        }
    }

    // Inner Records
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputTarget(
        @JsonProperty("targetStepName") @NotBlank String targetStepName,
        @JsonProperty("transportType") @NotNull TransportType transportType,
        @JsonProperty("grpcTransport") @Valid GrpcTransportConfig grpcTransport,
        @JsonProperty("kafkaTransport") @Valid KafkaTransportConfig kafkaTransport
    ) {
        @JsonCreator
        public OutputTarget(
            @JsonProperty("targetStepName") String targetStepName,
            @JsonProperty("transportType") TransportType transportType,
            @JsonProperty("grpcTransport") GrpcTransportConfig grpcTransport,
            @JsonProperty("kafkaTransport") KafkaTransportConfig kafkaTransport
        ) {
            this.targetStepName = Objects.requireNonNull(targetStepName, "targetStepName cannot be null");
            this.transportType = (transportType == null) ? TransportType.GRPC : transportType; // Defaulting
            this.grpcTransport = grpcTransport;
            this.kafkaTransport = kafkaTransport;

            // Validations for transport configs
            if (this.transportType == TransportType.KAFKA && this.kafkaTransport == null) {
                throw new IllegalArgumentException("OutputTarget: KafkaTransportConfig must be provided when transportType is KAFKA for targetStepName '" + targetStepName + "'.");
            }
            if (this.transportType != TransportType.KAFKA && this.kafkaTransport != null) {
                throw new IllegalArgumentException("OutputTarget: KafkaTransportConfig should only be provided when transportType is KAFKA (found type: " + this.transportType + ") for targetStepName '" + targetStepName + "'.");
            }
            if (this.transportType == TransportType.GRPC && this.grpcTransport == null) {
                throw new IllegalArgumentException("OutputTarget: GrpcTransportConfig must be provided when transportType is GRPC for targetStepName '" + targetStepName + "'.");
            }
            if (this.transportType != TransportType.GRPC && this.grpcTransport != null) {
                throw new IllegalArgumentException("OutputTarget: GrpcTransportConfig should only be provided when transportType is GRPC (found type: " + this.transportType + ") for targetStepName '" + targetStepName + "'.");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonConfigOptions(
        @JsonProperty("jsonConfig") JsonNode jsonConfig,
        @JsonProperty("configParams") Map<String, String> configParams
    ) {
        // Provide a @JsonCreator if complex instantiation from JSON is needed,
        // or if default values for configParams (e.g., emptyMap) are desired when missing.
        @JsonCreator
        public JsonConfigOptions(
            @JsonProperty("jsonConfig") JsonNode jsonConfig,
            @JsonProperty("configParams") Map<String, String> configParams
        ) {
            this.jsonConfig = jsonConfig; // Can be null
            this.configParams = (configParams == null) ? Collections.emptyMap() : Map.copyOf(configParams); // Defensive copy
        }

        // Convenience constructor for tests (if you parse JSON string outside)
        public JsonConfigOptions(JsonNode jsonNode) {
            this(jsonNode, Collections.emptyMap());
        }
         // Convenience for tests if only configParams are needed
        public JsonConfigOptions(Map<String, String> configParams) {
            this(null, configParams);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProcessorInfo(
        @JsonProperty("grpcServiceName") String grpcServiceName,
        @JsonProperty("internalProcessorBeanName") String internalProcessorBeanName
    ) {
        // Validation moved to PipelineStepConfig main constructor for ProcessorInfo object
        // Or keep it here if ProcessorInfo can be instantiated independently with this rule.
        // For now, assuming PipelineStepConfig's constructor handles validating the passed ProcessorInfo.
    }
}