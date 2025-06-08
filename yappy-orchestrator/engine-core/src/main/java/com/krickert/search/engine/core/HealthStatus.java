package com.krickert.search.engine.core;

/**
 * Health status of a service instance.
 */
public enum HealthStatus {
    /**
     * Service is healthy and ready to receive requests
     */
    HEALTHY,
    
    /**
     * Service is unhealthy and should not receive requests
     */
    UNHEALTHY,
    
    /**
     * Health status is unknown (no recent health check)
     */
    UNKNOWN,
    
    /**
     * Service is in maintenance mode
     */
    MAINTENANCE
}