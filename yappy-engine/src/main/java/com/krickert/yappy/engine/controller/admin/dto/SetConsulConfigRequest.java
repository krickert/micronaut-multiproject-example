package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SetConsulConfigRequest {

    @NotBlank(message = "Consul host must be provided.")
    @JsonProperty("host")
    private String host;

    @Min(value = 1, message = "Consul port must be a positive integer.")
    @JsonProperty("port")
    private int port;

    @JsonProperty("aclToken")
    private String aclToken; // Optional

    public SetConsulConfigRequest() {
    }

    @JsonCreator
    public SetConsulConfigRequest(
            @JsonProperty("host") String host, 
            @JsonProperty("port") int port, 
            @JsonProperty("aclToken") String aclToken) {
        this.host = host;
        this.port = port;
        this.aclToken = aclToken;
    }
}
