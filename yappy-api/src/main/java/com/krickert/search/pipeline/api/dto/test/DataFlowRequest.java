package com.krickert.search.pipeline.api.dto.test;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public class DataFlowRequest {
    @NotBlank
    private String pipelineId;
    
    @NotNull
    private String inputData;
    
    private String contentType = "text/plain";
    private Map<String, Object> metadata;
    private boolean trace = false;
    
    // Getters and setters
    public String getPipelineId() {
        return pipelineId;
    }
    
    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }
    
    public String getInputData() {
        return inputData;
    }
    
    public void setInputData(String inputData) {
        this.inputData = inputData;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }
    
    public Map<String, Object> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }
    
    public boolean isTrace() {
        return trace;
    }
    
    public void setTrace(boolean trace) {
        this.trace = trace;
    }
}