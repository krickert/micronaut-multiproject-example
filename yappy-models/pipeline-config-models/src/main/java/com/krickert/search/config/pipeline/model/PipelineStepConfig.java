package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory; // Added for default JsonNode
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
        @JsonProperty("stepName") @NotBlank String stepName,
        @JsonProperty("stepType") @NotNull StepType stepType,
        @JsonProperty("description") String description,
        @JsonProperty("customConfigSchemaId") String customConfigSchemaId,
        @JsonProperty("customConfig") @Valid JsonConfigOptions customConfig, // Inner record JsonConfigOptions
        @JsonProperty("kafkaInputs") @Valid List<KafkaInputDefinition> kafkaInputs,
        @JsonProperty("outputs") @Valid Map<String, OutputTarget> outputs,
        @JsonProperty("maxRetries") Integer maxRetries,
        @JsonProperty("retryBackoffMs") Long retryBackoffMs,
        @JsonProperty("maxRetryBackoffMs") Long maxRetryBackoffMs,
        @JsonProperty("retryBackoffMultiplier") Double retryBackoffMultiplier,
        @JsonProperty("stepTimeoutMs") Long stepTimeoutMs,
        @JsonProperty("processorInfo") @NotNull(message = "Processor information (processorInfo) must be provided.") @Valid ProcessorInfo processorInfo
) {

    // Canonical constructor (as provided by you, with validation)
    @JsonCreator
    public PipelineStepConfig(
            @JsonProperty("stepName") String stepName,
            @JsonProperty("stepType") StepType stepType,
            @JsonProperty("description") String description,
            @JsonProperty("customConfigSchemaId") String customConfigSchemaId,
            @JsonProperty("customConfig") JsonConfigOptions customConfig,
            @JsonProperty("kafkaInputs") List<KafkaInputDefinition> kafkaInputs,
            @JsonProperty("outputs") Map<String, OutputTarget> outputs,
            @JsonProperty("maxRetries") Integer maxRetries,
            @JsonProperty("retryBackoffMs") Long retryBackoffMs,
            @JsonProperty("maxRetryBackoffMs") Long maxRetryBackoffMs,
            @JsonProperty("retryBackoffMultiplier") Double retryBackoffMultiplier,
            @JsonProperty("stepTimeoutMs") Long stepTimeoutMs,
            @JsonProperty("processorInfo") ProcessorInfo processorInfo
    ) {
        this.stepName = Objects.requireNonNull(stepName, "stepName cannot be null");
        if (stepName.isBlank()) throw new IllegalArgumentException("stepName cannot be blank");

        this.stepType = Objects.requireNonNull(stepType, "stepType cannot be null");
        this.description = description;
        this.customConfigSchemaId = customConfigSchemaId;
        this.customConfig = customConfig; // Nullable, or provide default if desired

        this.kafkaInputs = (kafkaInputs == null) ? Collections.emptyList() : List.copyOf(kafkaInputs);
        this.outputs = (outputs == null) ? Collections.emptyMap() : Map.copyOf(outputs);
        this.maxRetries = (maxRetries == null || maxRetries < 0) ? 0 : maxRetries;
        this.retryBackoffMs = (retryBackoffMs == null || retryBackoffMs < 0) ? 1000L : retryBackoffMs;
        this.maxRetryBackoffMs = (maxRetryBackoffMs == null || maxRetryBackoffMs < 0) ? 30000L : maxRetryBackoffMs;
        this.retryBackoffMultiplier = (retryBackoffMultiplier == null || retryBackoffMultiplier <= 0) ? 2.0 : retryBackoffMultiplier;
        this.stepTimeoutMs = (stepTimeoutMs == null || stepTimeoutMs < 0) ? null : stepTimeoutMs;
        this.processorInfo = Objects.requireNonNull(processorInfo, "processorInfo cannot be null");

        // ProcessorInfo validation
        if (this.processorInfo.grpcServiceName() != null && !this.processorInfo.grpcServiceName().isBlank() &&
                this.processorInfo.internalProcessorBeanName() != null && !this.processorInfo.internalProcessorBeanName().isBlank()) {
            throw new IllegalArgumentException("ProcessorInfo cannot have both grpcServiceName and internalProcessorBeanName set.");
        }
        if ((this.processorInfo.grpcServiceName() == null || this.processorInfo.grpcServiceName().isBlank()) &&
                (this.processorInfo.internalProcessorBeanName() == null || this.processorInfo.internalProcessorBeanName().isBlank())) {
            throw new IllegalArgumentException("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName set.");
        }
    }

    // --- START: Added Helper Constructors ---

    /**
     * Constructor matching the parameters:
     * stepName, stepType, description, customConfigSchemaId, customConfig,
     * outputs, maxRetries, retryBackoffMs, maxRetryBackoffMs,
     * retryBackoffMultiplier, stepTimeoutMs, processorInfo.
     * Defaults kafkaInputs to an empty list.
     */
    public PipelineStepConfig(
            String stepName,
            StepType stepType,
            String description,
            String customConfigSchemaId,
            JsonConfigOptions customConfig,
            Map<String, OutputTarget> outputs,
            Integer maxRetries,
            Long retryBackoffMs,
            Long maxRetryBackoffMs,
            Double retryBackoffMultiplier,
            Long stepTimeoutMs,
            ProcessorInfo processorInfo
    ) {
        this(
                stepName,
                stepType,
                description,
                customConfigSchemaId,
                customConfig,
                Collections.emptyList(), // Default for kafkaInputs
                outputs,
                maxRetries,
                retryBackoffMs,
                maxRetryBackoffMs,
                retryBackoffMultiplier,
                stepTimeoutMs,
                processorInfo
        );
    }

    public PipelineStepConfig(
            String stepName,
            StepType stepType,
            ProcessorInfo processorInfo,
            PipelineStepConfig.JsonConfigOptions customConfig, // Ensure this refers to the inner record
            String customConfigSchemaId
    ) {
        this(
                stepName,
                stepType,
                "Test Description for " + stepName, // default description
                customConfigSchemaId,
                customConfig,
                Collections.emptyList(), // default kafkaInputs
                Collections.emptyMap(),  // default outputs
                0,       // default maxRetries
                1000L,   // default retryBackoffMs
                30000L,  // default maxRetryBackoffMs
                2.0,     // default retryBackoffMultiplier
                null,    // default stepTimeoutMs
                processorInfo
        );
    }

    public PipelineStepConfig(
            String stepName,
            StepType stepType,
            ProcessorInfo processorInfo,
            PipelineStepConfig.JsonConfigOptions customConfig // Inner record
    ) {
        this(stepName, stepType, processorInfo, customConfig, null);
    }

    public PipelineStepConfig(
            String stepName,
            StepType stepType,
            ProcessorInfo processorInfo
    ) {
        this(
                stepName,
                stepType,
                processorInfo,
                new PipelineStepConfig.JsonConfigOptions(JsonNodeFactory.instance.objectNode(), Collections.emptyMap()), // Default empty custom config
                null
        );
    }
    // --- END: Added Helper Constructors ---

    // Inner Records (OutputTarget, JsonConfigOptions, ProcessorInfo)
    // Assuming they are defined as per the uploaded PipelineStepConfig.java
    // ... (OutputTarget, JsonConfigOptions, ProcessorInfo as defined in your uploaded file) ...
    // For brevity, not repeating them here but they should be part of this file.
    // Ensuring the JsonConfigOptions inner record is what we expect:
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonConfigOptions(
            @JsonProperty("jsonConfig") JsonNode jsonConfig, // This should be JsonNode
            @JsonProperty("configParams") Map<String, String> configParams
    ) {
        @JsonCreator
        public JsonConfigOptions(
                @JsonProperty("jsonConfig") JsonNode jsonConfig,
                @JsonProperty("configParams") Map<String, String> configParams
        ) {
            this.jsonConfig = jsonConfig; // Can be null if not provided in JSON
            this.configParams = (configParams == null) ? Collections.emptyMap() : Map.copyOf(configParams);
        }

        // Convenience for tests if only jsonNode is needed
        public JsonConfigOptions(JsonNode jsonNode) {
            this(jsonNode, Collections.emptyMap());
        }
        // Convenience for tests if only configParams are needed
        public JsonConfigOptions(Map<String, String> configParams) {
            this(null, configParams); // Or JsonNodeFactory.instance.objectNode() if jsonConfig should never be null in the object
        }
    }

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
            if (this.targetStepName.isBlank()) throw new IllegalArgumentException("targetStepName cannot be blank");

            this.transportType = (transportType == null) ? TransportType.GRPC : transportType;
            this.grpcTransport = grpcTransport;
            this.kafkaTransport = kafkaTransport;

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
    public record ProcessorInfo(
            @JsonProperty("grpcServiceName") String grpcServiceName,
            @JsonProperty("internalProcessorBeanName") String internalProcessorBeanName
    ) {
        @JsonCreator
        public ProcessorInfo(
                @JsonProperty("grpcServiceName") String grpcServiceName,
                @JsonProperty("internalProcessorBeanName") String internalProcessorBeanName
        ) {
            boolean grpcSet = grpcServiceName != null && !grpcServiceName.isBlank();
            boolean beanSet = internalProcessorBeanName != null && !internalProcessorBeanName.isBlank();

            if (grpcSet && beanSet) {
                throw new IllegalArgumentException("ProcessorInfo cannot have both grpcServiceName and internalProcessorBeanName set.");
            }
            if (!grpcSet && !beanSet) {
                throw new IllegalArgumentException("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName set.");
            }
            this.grpcServiceName = grpcServiceName;
            this.internalProcessorBeanName = internalProcessorBeanName;
        }
    }
}