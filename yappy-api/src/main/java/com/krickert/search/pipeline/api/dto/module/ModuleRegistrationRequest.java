package com.krickert.search.pipeline.api.dto.module;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.Map;

@Serdeable
@Schema(description = "Request to register a new module")
public class ModuleRegistrationRequest {
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]+$", message = "Service ID must be lowercase alphanumeric with hyphens")
    private String serviceId;
    
    @NotBlank
    private String name;
    
    private String description;
    private String version;
    
    @NotBlank
    private String serviceAddress;
    
    @Min(1)
    @Max(65535)
    private Integer servicePort;
    
    private String inputType;
    private String outputType;
    private List<String> tags;
    private Map<String, Object> configuration;
    private String healthEndpoint;
    
    // Getters and setters
    public String getServiceId() {
        return serviceId;
    }
    
    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
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
    
    public Map<String, Object> getConfiguration() {
        return configuration;
    }
    
    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }
    
    public String getHealthEndpoint() {
        return healthEndpoint;
    }
    
    public void setHealthEndpoint(String healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }
}