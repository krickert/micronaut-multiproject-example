package com.krickert.yappy.engine.controller.admin.responses;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class ConsulConfigResponse {
    private String host;
    private int port;
    private String aclToken;
    private boolean configured;

    // Default constructor
    public ConsulConfigResponse() {}

    // Builder pattern for easier construction
    public static Builder builder() {
        return new Builder();
    }

    // Getters and setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    public String getAclToken() { return aclToken; }
    public void setAclToken(String aclToken) { this.aclToken = aclToken; }
    
    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }

    public static class Builder {
        private final ConsulConfigResponse response = new ConsulConfigResponse();

        public Builder host(String host) {
            response.host = host;
            return this;
        }

        public Builder port(int port) {
            response.port = port;
            return this;
        }

        public Builder aclToken(String aclToken) {
            response.aclToken = aclToken;
            return this;
        }

        public Builder configured(boolean configured) {
            response.configured = configured;
            return this;
        }

        public ConsulConfigResponse build() {
            return response;
        }
    }
}