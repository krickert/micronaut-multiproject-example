package com.krickert.search.pipeline.api.dto.module;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Serdeable
@Schema(description = "Module view with current status and configuration")
public class ModuleView {
    private String id;
    private String name;
    private String description;
    private String version;
    private String serviceAddress;
    private Integer servicePort;
    private String inputType;
    private String outputType;
    private List<String> tags;
    private boolean healthy;
    private Instant lastHealthCheck;
    private Map<String, Object> configuration;
    
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
    
    public String getVersion() {
        return version;
    }
    
    public void setVersion(String version) {
        this.version = version;
    }
    
    public String getServiceAddress() {
        return serviceAddress;
    }
    
    public void setServiceAddress(String serviceAddress) {
        this.serviceAddress = serviceAddress;
    }
    
    public Integer getServicePort() {
        return servicePort;
    }
    
    public void setServicePort(Integer servicePort) {
        this.servicePort = servicePort;
    }
    
    public String getInputType() {
        return inputType;
    }
    
    public void setInputType(String inputType) {
        this.inputType = inputType;
    }
    
    public String getOutputType() {
        return outputType;
    }
    
    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }
    
    public List<String> getTags() {
        return tags;
    }
    
    public void setTags(List<String> tags) {
        this.tags = tags;
    }
    
    public boolean isHealthy() {
        return healthy;
    }
    
    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
    }
    
    public Instant getLastHealthCheck() {
        return lastHealthCheck;
    }
    
    public void setLastHealthCheck(Instant lastHealthCheck) {
        this.lastHealthCheck = lastHealthCheck;
    }
    
    public Map<String, Object> getConfiguration() {
        return configuration;
    }
    
    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }
}