package com.krickert.search.engine.core;

import java.util.Map;
import java.util.Set;

/**
 * Represents a discovered service instance.
 * 
 * This contains all the information needed to connect to a module
 * instance via gRPC.
 */
public record ServiceInstance(
    String serviceId,
    String serviceName,
    String host,
    int port,
    HealthStatus healthStatus,
    Set<String> tags,
    Map<String, String> metadata
) {
    
    /**
     * Get the gRPC target string for this instance.
     * 
     * @return The target in format "host:port"
     */
    public String getGrpcTarget() {
        return host + ":" + port;
    }
    
    /**
     * Check if this instance has a specific tag.
     * 
     * @param tag The tag to check
     * @return true if the instance has the tag
     */
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
    
    /**
     * Get a metadata value.
     * 
     * @param key The metadata key
     * @return The value, or null if not present
     */
    public String getMetadata(String key) {
        return metadata.get(key);
    }
}