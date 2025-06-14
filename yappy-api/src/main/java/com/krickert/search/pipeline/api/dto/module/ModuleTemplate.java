package com.krickert.search.pipeline.api.dto.module;

import java.util.Map;

public class ModuleTemplate {
    private String id;
    private String name;
    private String description;
    private String type;
    private Map<String, Object> defaultConfiguration;
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public Map<String, Object> getDefaultConfiguration() {
        return defaultConfiguration;
    }
    
    public void setDefaultConfiguration(Map<String, Object> defaultConfiguration) {
        this.defaultConfiguration = defaultConfiguration;
    }
}