package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class SelectClusterRequest {

    @NotBlank(message = "Cluster name must be provided.")
    private String clusterName;

    public SelectClusterRequest() {
    }

    public SelectClusterRequest(String clusterName) {
        this.clusterName = clusterName;
    }

}
