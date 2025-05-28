package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SelectClusterRequest {

    @NotBlank(message = "Cluster name must be provided.")
    @JsonProperty("clusterName")
    private String clusterName;

    public SelectClusterRequest() {
    }

    @JsonCreator
    public SelectClusterRequest(@JsonProperty("clusterName") String clusterName) {
        this.clusterName = clusterName;
    }

}
