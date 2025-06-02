package com.krickert.yappy.engine.controller.admin.requests;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class CreateClusterRequest {
    private String clusterName;
    private String description;

    // Default constructor
    public CreateClusterRequest() {}

    // Getters and setters
    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}