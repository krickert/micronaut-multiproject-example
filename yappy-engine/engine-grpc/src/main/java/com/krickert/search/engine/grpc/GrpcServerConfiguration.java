package com.krickert.search.engine.grpc;

import io.micronaut.context.annotation.ConfigurationProperties;
import jakarta.inject.Singleton;

/**
 * Configuration for the gRPC server.
 * This configuration is loaded from application properties.
 * 
 * Note: Micronaut gRPC automatically creates the ServerBuilder
 * based on the grpc.server configuration in application.yml
 */
@Singleton
@ConfigurationProperties("engine.grpc")
public class GrpcServerConfiguration {
    
    private boolean eventDrivenEnabled = true;
    
    public boolean isEventDrivenEnabled() {
        return eventDrivenEnabled;
    }
    
    public void setEventDrivenEnabled(boolean eventDrivenEnabled) {
        this.eventDrivenEnabled = eventDrivenEnabled;
    }
}