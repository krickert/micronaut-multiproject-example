package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;

@Data
@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EngineStatusResponse {

    private String activeClusterName;
    private Boolean isConfigStale;
    private String currentConfigVersionIdentifier;
    private Object micronautHealth; // Can be Map<String, Object> or a specific DTO

    public EngineStatusResponse() {
    }

    public EngineStatusResponse(String activeClusterName, Boolean isConfigStale, String currentConfigVersionIdentifier, Object micronautHealth) {
        this.activeClusterName = activeClusterName;
        this.isConfigStale = isConfigStale;
        this.currentConfigVersionIdentifier = currentConfigVersionIdentifier;
        this.micronautHealth = micronautHealth;
    }
}
