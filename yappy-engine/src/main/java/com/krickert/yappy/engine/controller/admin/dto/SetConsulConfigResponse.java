package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

@Data
@Introspected
@Serdeable
public class SetConsulConfigResponse {

    private boolean success;
    private String message;
    private ConsulConfigResponse currentConfig;

    public SetConsulConfigResponse() {
    }

    public SetConsulConfigResponse(boolean success, String message, ConsulConfigResponse currentConfig) {
        this.success = success;
        this.message = message;
        this.currentConfig = currentConfig;
    }

}
