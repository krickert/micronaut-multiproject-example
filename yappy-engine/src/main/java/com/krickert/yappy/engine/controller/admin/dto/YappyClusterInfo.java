package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class YappyClusterInfo {

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("clusterId")
    private String clusterId; // The full Consul key

    @JsonProperty("status")
    private String status;

    public YappyClusterInfo() {
    }

    @JsonCreator
    public YappyClusterInfo(
            @JsonProperty("clusterName") String clusterName, 
            @JsonProperty("clusterId") String clusterId, 
            @JsonProperty("status") String status) {
        this.clusterName = clusterName;
        this.clusterId = clusterId;
        this.status = status;
    }
}
