package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateClusterRequest {

    @NotBlank(message = "Cluster name must be provided.")
    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("firstPipelineName")
    private String firstPipelineName; // Optional

    @Valid
    @JsonProperty("initialModules")
    private List<PipelineModuleInput> initialModules; // Optional

    public CreateClusterRequest() {
    }

    @JsonCreator
    public CreateClusterRequest(
            @JsonProperty("clusterName") String clusterName, 
            @JsonProperty("firstPipelineName") String firstPipelineName, 
            @JsonProperty("initialModules") List<PipelineModuleInput> initialModules) {
        this.clusterName = clusterName;
        this.firstPipelineName = firstPipelineName;
        this.initialModules = initialModules;
    }
}
