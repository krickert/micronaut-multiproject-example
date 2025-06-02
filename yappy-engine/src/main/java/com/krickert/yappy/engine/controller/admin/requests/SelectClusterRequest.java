package com.krickert.yappy.engine.controller.admin.requests;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class SelectClusterRequest {
    private String clusterName;

    // Default constructor
    public SelectClusterRequest() {}

    // Getters and setters
    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }
}