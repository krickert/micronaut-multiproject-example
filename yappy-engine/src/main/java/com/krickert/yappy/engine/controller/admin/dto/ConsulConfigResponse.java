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
public class ConsulConfigResponse {

    @JsonProperty("host")
    private String host;

    @JsonProperty("port")
    private String port;

    @JsonProperty("aclToken")
    private String aclToken;

    @JsonProperty("selectedYappyClusterName")
    private String selectedYappyClusterName;

    public ConsulConfigResponse() {
    }

    @JsonCreator
    public ConsulConfigResponse(
            @JsonProperty("host") String host, 
            @JsonProperty("port") String port, 
            @JsonProperty("aclToken") String aclToken, 
            @JsonProperty("selectedYappyClusterName") String selectedYappyClusterName) {
        this.host = host;
        this.port = port;
        this.aclToken = aclToken;
        this.selectedYappyClusterName = selectedYappyClusterName;
    }
}
