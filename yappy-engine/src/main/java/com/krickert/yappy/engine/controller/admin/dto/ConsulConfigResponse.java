package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class ConsulConfigResponse {

    private String host;
    private String port;
    private String aclToken;
    private String selectedYappyClusterName;

    public ConsulConfigResponse() {
    }

    public ConsulConfigResponse(String host, String port, String aclToken, String selectedYappyClusterName) {
        this.host = host;
        this.port = port;
        this.aclToken = aclToken;
        this.selectedYappyClusterName = selectedYappyClusterName;
    }
}
