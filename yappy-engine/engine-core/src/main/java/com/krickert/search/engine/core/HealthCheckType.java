package com.krickert.search.engine.core;

/**
 * Type of health check for service monitoring.
 */
public enum HealthCheckType {
    /**
     * HTTP GET request to health endpoint
     */
    HTTP,
    
    /**
     * gRPC health check protocol
     */
    GRPC,
    
    /**
     * Simple TCP connection check
     */
    TCP,
    
    /**
     * TTL-based health check (service must update periodically)
     */
    TTL
}