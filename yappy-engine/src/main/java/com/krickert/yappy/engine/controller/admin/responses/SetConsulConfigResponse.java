package com.krickert.yappy.engine.controller.admin.responses;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class SetConsulConfigResponse {
    private boolean success;
    private String message;

    // Default constructor
    public SetConsulConfigResponse() {}

    // Constructor with all fields
    public SetConsulConfigResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    // Getters and setters
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}