package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    @JsonProperty("activeClusterName")
    private String activeClusterName;

    @JsonProperty("isConfigStale")
    private Boolean isConfigStale;

    @JsonProperty("currentConfigVersionIdentifier")
    private String currentConfigVersionIdentifier;

    @JsonProperty("micronautHealth")
    private Object micronautHealth; // Can be Map<String, Object> or a specific DTO

    public EngineStatusResponse() {
    }

    @JsonCreator
    public EngineStatusResponse(
            @JsonProperty("activeClusterName") String activeClusterName, 
            @JsonProperty("isConfigStale") Boolean isConfigStale, 
            @JsonProperty("currentConfigVersionIdentifier") String currentConfigVersionIdentifier, 
            @JsonProperty("micronautHealth") Object micronautHealth) {
        this.activeClusterName = activeClusterName;
        this.isConfigStale = isConfigStale;
        this.currentConfigVersionIdentifier = currentConfigVersionIdentifier;
        this.micronautHealth = micronautHealth;
    }
}
