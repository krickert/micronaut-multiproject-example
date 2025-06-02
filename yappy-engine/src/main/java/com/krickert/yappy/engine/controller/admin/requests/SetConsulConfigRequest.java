package com.krickert.yappy.engine.controller.admin.requests;

import io.micronaut.serde.annotation.Serdeable;

@Serdeable
public class SetConsulConfigRequest {
    private String host;
    private int port;
    private String aclToken;

    // Default constructor
    public SetConsulConfigRequest() {}

    // Getters and setters
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    public String getAclToken() { return aclToken; }
    public void setAclToken(String aclToken) { this.aclToken = aclToken; }
}