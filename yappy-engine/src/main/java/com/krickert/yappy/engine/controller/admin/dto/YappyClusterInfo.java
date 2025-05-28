package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class YappyClusterInfo {

    private String clusterName;
    private String clusterId; // The full Consul key
    private String status;

    public YappyClusterInfo() {
    }

    public YappyClusterInfo(String clusterName, String clusterId, String status) {
        this.clusterName = clusterName;
        this.clusterId = clusterId;
        this.status = status;
    }
}
