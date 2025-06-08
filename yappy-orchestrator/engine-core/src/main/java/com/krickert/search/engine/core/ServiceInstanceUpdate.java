package com.krickert.search.engine.core;

import java.time.Instant;

/**
 * Represents an update to service instances.
 * 
 * Used for reactive streams when watching service changes.
 */
public record ServiceInstanceUpdate(
    UpdateType type,
    ServiceInstance instance,
    Instant timestamp
) {
    
    public enum UpdateType {
        /**
         * New instance added
         */
        ADDED,
        
        /**
         * Instance modified (health status, metadata, etc.)
         */
        MODIFIED,
        
        /**
         * Instance removed
         */
        REMOVED
    }
}