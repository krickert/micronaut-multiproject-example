package com.krickert.search.engine.core;

import java.time.Instant;

/**
 * Event fired when configuration changes.
 * 
 * This is a simplified version for the engine core.
 * The implementation will adapt from the actual event
 * in yappy-consul-config.
 */
public record ConfigurationChangeEvent(
    ChangeType changeType,
    String configPath,
    String configType,
    Instant timestamp
) {
    
    public enum ChangeType {
        CREATED,
        UPDATED,
        DELETED
    }
}