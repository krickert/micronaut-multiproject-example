package com.krickert.search.engine.core;

import java.time.Duration;

/**
 * Health check configuration for service registration.
 */
public record HealthCheckConfig(
    HealthCheckType type,
    String endpoint,
    Duration interval,
    Duration timeout,
    Duration deregisterAfter
) {
    
    /**
     * Create a gRPC health check config with defaults.
     */
    public static HealthCheckConfig grpc(String serviceName) {
        return new HealthCheckConfig(
            HealthCheckType.GRPC,
            serviceName,
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(1)
        );
    }
    
    /**
     * Create an HTTP health check config.
     */
    public static HealthCheckConfig http(String path) {
        return new HealthCheckConfig(
            HealthCheckType.HTTP,
            path,
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(1)
        );
    }
    
    /**
     * Create a TCP health check config.
     */
    public static HealthCheckConfig tcp() {
        return new HealthCheckConfig(
            HealthCheckType.TCP,
            "",
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            Duration.ofMinutes(1)
        );
    }
}