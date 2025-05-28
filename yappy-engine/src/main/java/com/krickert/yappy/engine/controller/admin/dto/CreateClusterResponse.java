package com.krickert.yappy.engine.controller.admin.dto;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable
public class CreateClusterResponse {

    private boolean success;
    private String message;
    private String clusterName;
    private String seededConfigPath; // e.g., "yappy/pipeline-clusters/YOUR_CLUSTER_NAME"

    public CreateClusterResponse() {
    }

    public CreateClusterResponse(boolean success, String message, String clusterName, String seededConfigPath) {
        this.success = success;
        this.message = message;
        this.clusterName = clusterName;
        this.seededConfigPath = seededConfigPath;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getClusterName() {
        return clusterName;
    }

    public void setClusterName(String clusterName) {
        this.clusterName = clusterName;
    }

    public String getSeededConfigPath() {
        return seededConfigPath;
    }

    public void setSeededConfigPath(String seededConfigPath) {
        this.seededConfigPath = seededConfigPath;
    }
}
