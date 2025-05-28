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
public class SetConsulConfigResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("currentConfig")
    private ConsulConfigResponse currentConfig;

    public SetConsulConfigResponse() {
    }

    @JsonCreator
    public SetConsulConfigResponse(
            @JsonProperty("success") boolean success, 
            @JsonProperty("message") String message, 
            @JsonProperty("currentConfig") ConsulConfigResponse currentConfig) {
        this.success = success;
        this.message = message;
        this.currentConfig = currentConfig;
    }

}
