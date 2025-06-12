package com.krickert.search.commons.events;

/**
 * Priority levels for events in the system.
 */
public enum EventPriority {
    /**
     * Low priority - can be processed when resources are available.
     */
    LOW,
    
    /**
     * Normal priority - standard processing.
     */
    NORMAL,
    
    /**
     * High priority - should be processed before normal priority events.
     */
    HIGH,
    
    /**
     * Critical priority - must be processed immediately.
     */
    CRITICAL
}