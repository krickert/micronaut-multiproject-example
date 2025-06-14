package com.krickert.search.pipeline.api.dto.test;

import java.util.List;
import java.util.Map;

public class DataFlowResponse {
    private String executionId;
    private String pipelineId;
    private boolean success;
    private String message;
    private List<DataFlowStep> steps;
    private Long totalExecutionTimeMs;
    private Map<String, Object> finalOutput;
    
    // Getters and setters
    public String getExecutionId() {
        return executionId;
    }
    
    public void setExecutionId(String executionId) {
        this.executionId = executionId;
    }
    
    public String getPipelineId() {
        return pipelineId;
    }
    
    public void setPipelineId(String pipelineId) {
        this.pipelineId = pipelineId;
    }
    
    public boolean isSuccess() {
        return success;
    }
    
    public void setSuccess(boolean success) {
        this.success = success;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    public List<DataFlowStep> getSteps() {
        return steps;
    }
    
    public void setSteps(List<DataFlowStep> steps) {
        this.steps = steps;
    }
    
    public Long getTotalExecutionTimeMs() {
        return totalExecutionTimeMs;
    }
    
    public void setTotalExecutionTimeMs(Long totalExecutionTimeMs) {
        this.totalExecutionTimeMs = totalExecutionTimeMs;
    }
    
    public Map<String, Object> getFinalOutput() {
        return finalOutput;
    }
    
    public void setFinalOutput(Map<String, Object> finalOutput) {
        this.finalOutput = finalOutput;
    }
    
    public static class DataFlowStep {
        private String stepName;
        private String moduleId;
        private boolean success;
        private Long executionTimeMs;
        private String input;
        private String output;
        private String error;
        
        // Getters and setters
        public String getStepName() {
            return stepName;
        }
        
        public void setStepName(String stepName) {
            this.stepName = stepName;
        }
        
        public String getModuleId() {
            return moduleId;
        }
        
        public void setModuleId(String moduleId) {
            this.moduleId = moduleId;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public Long getExecutionTimeMs() {
            return executionTimeMs;
        }
        
        public void setExecutionTimeMs(Long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
        }
        
        public String getInput() {
            return input;
        }
        
        public void setInput(String input) {
            this.input = input;
        }
        
        public String getOutput() {
            return output;
        }
        
        public void setOutput(String output) {
            this.output = output;
        }
        
        public String getError() {
            return error;
        }
        
        public void setError(String error) {
            this.error = error;
        }
    }
}