package com.krickert.search.pipeline.api.dto.test;

import jakarta.validation.constraints.NotNull;

public class GenerateTestDataRequest {
    @NotNull
    private String format;
    
    private String template;
    private Integer size;
    private java.util.Map<String, Object> parameters;
    
    // Getters and setters
    public String getFormat() {
        return format;
    }
    
    public void setFormat(String format) {
        this.format = format;
    }
    
    public String getTemplate() {
        return template;
    }
    
    public void setTemplate(String template) {
        this.template = template;
    }
    
    public Integer getSize() {
        return size;
    }
    
    public void setSize(Integer size) {
        this.size = size;
    }
    
    public java.util.Map<String, Object> getParameters() {
        return parameters;
    }
    
    public void setParameters(java.util.Map<String, Object> parameters) {
        this.parameters = parameters;
    }
}