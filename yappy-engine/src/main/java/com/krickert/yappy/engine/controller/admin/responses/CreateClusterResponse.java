package com.krickert.yappy.engine.controller.admin.responses;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class CreateClusterResponse {
    private boolean success;
    private String message;
    private String clusterName;

    // Default constructor
    public CreateClusterResponse() {}

    // Getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }
}