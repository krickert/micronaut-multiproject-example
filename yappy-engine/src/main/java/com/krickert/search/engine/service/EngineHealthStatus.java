package com.krickert.search.engine.service;

import java.time.Instant;
import java.util.Map;

/**
 * Represents the health status of the engine and its components.
 */
public record EngineHealthStatus(
    Status overallStatus,
    Map<String, ComponentHealth> componentStatuses,
    Instant lastChecked
) {
    
    public enum Status {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
        UNKNOWN
    }
    
    public record ComponentHealth(
        String componentName,
        Status status,
        String message,
        Map<String, Object> metadata
    ) {}
}