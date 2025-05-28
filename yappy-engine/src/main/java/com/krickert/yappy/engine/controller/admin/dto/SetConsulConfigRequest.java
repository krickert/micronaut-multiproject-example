package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class SetConsulConfigRequest {

    @NotBlank(message = "Consul host must be provided.")
    private String host;

    @Min(value = 1, message = "Consul port must be a positive integer.")
    private int port;

    private String aclToken; // Optional

    public SetConsulConfigRequest() {
    }

    public SetConsulConfigRequest(String host, int port, String aclToken) {
        this.host = host;
        this.port = port;
        this.aclToken = aclToken;
    }
}
