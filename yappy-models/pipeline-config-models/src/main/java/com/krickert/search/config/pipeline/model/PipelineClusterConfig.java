package com.krickert.search.config.pipeline.model; // Adjusted package

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;
// No Micronaut imports

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineClusterConfig {

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("pipelineGraphConfig")
    private PipelineGraphConfig pipelineGraphConfig;

    @JsonProperty("pipelineModuleMap")
    private PipelineModuleMap pipelineModuleMap;

    @JsonProperty("allowedKafkaTopics")
    private Set<String> allowedKafkaTopics;

    @JsonProperty("allowedGrpcServices")
    private Set<String> allowedGrpcServices;
}