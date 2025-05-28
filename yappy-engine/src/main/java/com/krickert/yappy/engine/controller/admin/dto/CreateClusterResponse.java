package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateClusterResponse {

    @JsonProperty("success")
    private boolean success;

    @JsonProperty("message")
    private String message;

    @JsonProperty("clusterName")
    private String clusterName;

    @JsonProperty("seededConfigPath")
    private String seededConfigPath; // e.g., "yappy/pipeline-clusters/YOUR_CLUSTER_NAME"

    public CreateClusterResponse() {
    }

    @JsonCreator
    public CreateClusterResponse(
            @JsonProperty("success") boolean success, 
            @JsonProperty("message") String message, 
            @JsonProperty("clusterName") String clusterName, 
            @JsonProperty("seededConfigPath") String seededConfigPath) {
        this.success = success;
        this.message = message;
        this.clusterName = clusterName;
        this.seededConfigPath = seededConfigPath;
    }

    @JsonProperty("success")
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @JsonProperty("clusterName")
    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    @JsonProperty("seededConfigPath")
    public String getSeededConfigPath() {
        return seededConfigPath;
    }

    public void setSeededConfigPath(String seededConfigPath) {
        this.seededConfigPath = seededConfigPath;
    }
}
