package com.krickert.yappy.engine.controller.admin.responses;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class YappyClusterInfo {
    private String name;
    private String description;
    private boolean active;
    private int pipelineCount;

    // Default constructor
    public YappyClusterInfo() {}

    // Constructor with all fields
    public YappyClusterInfo(String name, String description, boolean active, int pipelineCount) {
        this.name = name;
        this.description = description;
        this.active = active;
        this.pipelineCount = pipelineCount;
    }

    // Getters and setters
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public int getPipelineCount() { return pipelineCount; }
    public void setPipelineCount(int pipelineCount) { this.pipelineCount = pipelineCount; }
}