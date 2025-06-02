package com.krickert.yappy.engine.controller.admin.responses;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class SelectClusterResponse {
    private boolean success;
    private String message;
    private String selectedCluster;

    // Default constructor
    public SelectClusterResponse() {}

    // Getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getSelectedCluster() { return selectedCluster; }
    public void setSelectedCluster(String selectedCluster) { this.selectedCluster = selectedCluster; }
}