package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
@Introspected
@Serdeable
public class CreateClusterRequest {

    @NotBlank(message = "Cluster name must be provided.")
    private String clusterName;

    private String firstPipelineName; // Optional

    @Valid
    private List<PipelineModuleInput> initialModules; // Optional

    public CreateClusterRequest() {
    }

    public CreateClusterRequest(String clusterName, String firstPipelineName, List<PipelineModuleInput> initialModules) {
        this.clusterName = clusterName;
        this.firstPipelineName = firstPipelineName;
        this.initialModules = initialModules;
    }
}
