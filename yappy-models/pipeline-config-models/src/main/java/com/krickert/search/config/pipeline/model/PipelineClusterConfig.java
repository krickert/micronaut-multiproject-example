package com.krickert.search.config.pipeline.model; // Or your actual package

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Set;
import java.util.Map;
import java.util.Collections;
// import java.util.stream.Collectors; // Not needed for this version

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineClusterConfig(
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
        @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
        @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics, // Correct annotation
        @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices // Correct annotation
) {
    // This is an EXPLICIT CANONICAL CONSTRUCTOR
    @JsonCreator
    public PipelineClusterConfig(
            @JsonProperty("clusterName") String clusterName,
            @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
            @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
            @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics, // This is the input parameter
            @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices // This is the input parameter
    ) {
        if (clusterName == null || clusterName.isBlank()) {
            throw new IllegalArgumentException("PipelineClusterConfig clusterName cannot be null or blank.");
        }
        this.clusterName = clusterName; // Assign validated parameter to the record component

        this.pipelineGraphConfig = pipelineGraphConfig; // Can be null, assigned directly
        this.pipelineModuleMap = pipelineModuleMap;     // Can be null, assigned directly

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