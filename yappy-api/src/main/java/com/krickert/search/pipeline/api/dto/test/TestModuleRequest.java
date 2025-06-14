package com.krickert.search.pipeline.api.dto.test;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class TestModuleRequest {
    @NotBlank
    private String testData;
    
    @NotNull
    private String contentType = "text/plain";
    
    private java.util.Map<String, String> metadata;
    
    // Getters and setters
    public String getTestData() {
        return testData;
    }
    
    public void setTestData(String testData) {
        this.testData = testData;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public java.util.Map<String, String> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(java.util.Map<String, String> metadata) {
        this.metadata = metadata;
    }
}