package com.krickert.search.config.pipeline.model; // Or your actual package

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.Collections;
import java.util.Set;
// import java.util.stream.Collectors; // Not needed for this version

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder(toBuilder = true)
@Serdeable
@Schema(description = "Pipeline cluster configuration")
public record PipelineClusterConfig(
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
        @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
        @JsonProperty("defaultPipelineName") String defaultPipelineName,
        @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics,
        @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices
) {
    // This is an EXPLICIT CANONICAL CONSTRUCTOR
    @JsonCreator
    public PipelineClusterConfig(
            @JsonProperty("clusterName") String clusterName,
            @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
            @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
            @JsonProperty("defaultPipelineName") String defaultPipelineName,
            @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics,
            @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices
    ) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("PipelineClusterConfig clusterName cannot be null or blank.");
        }
        this.clusterName = clusterName; // Assign validated parameter to the record component

        this.pipelineGraphConfig = pipelineGraphConfig; // Can be null, assigned directly
        this.pipelineModuleMap = pipelineModuleMap;     // Can be null, assigned directly
        this.defaultPipelineName = defaultPipelineName; // Can be null, assigned directly

        // Validate and normalize allowedKafkaTopics
        if (allowedKafkaTopics == null) {
            this.allowedKafkaTopics = Collections.emptySet(); // Assign default to the record component
        } else {
            for (String topic : allowedKafkaTopics) {
                if (topic == null || topic.isBlank()) {
                    throw new IllegalArgumentException("allowedKafkaTopics cannot contain null or blank strings.");
                }
            }
            this.allowedKafkaTopics = Set.copyOf(allowedKafkaTopics); // Assign immutable copy to the record component
        }

        // Validate and normalize allowedGrpcServices
        if (allowedGrpcServices == null) {
            this.allowedGrpcServices = Collections.emptySet(); // Assign default to the record component
        } else {
            for (String service : allowedGrpcServices) {
                if (service == null || service.isBlank()) {
                    throw new IllegalArgumentException("allowedGrpcServices cannot contain null or blank strings.");
                }
            }
            this.allowedGrpcServices = Set.copyOf(allowedGrpcServices); // Assign immutable copy to the record component
        }
    }
}
