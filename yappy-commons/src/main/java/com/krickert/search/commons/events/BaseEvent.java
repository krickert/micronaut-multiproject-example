package com.krickert.search.commons.events;

import io.micronaut.core.annotation.Introspected;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class for all events in the system.
 * Provides common fields and functionality for event tracking.
 */
@Introspected
public abstract class BaseEvent {
    
    private final String eventId;
    private final Instant timestamp;
    private final EventMetadata metadata;
    
    protected BaseEvent(EventMetadata metadata) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.metadata = metadata;
    }
    
    /**
     * Get the unique event ID.
     */
    public String getEventId() {
        return eventId;
    }
    
    /**
     * Get the event timestamp.
     */
    public Instant getTimestamp() {
        return timestamp;
    }
    
    /**
     * Get the event metadata.
     */
    public EventMetadata getMetadata() {
        return metadata;
    }
    
    /**
     * Get the correlation ID from metadata.
     */
    public String getCorrelationId() {
        return metadata.correlationId();
    }
    
    /**
     * Get the source module from metadata.
     */
    public String getSourceModule() {
        return metadata.sourceModule();
    }
    
    /**
     * Get the event priority from metadata.
     */
    public EventPriority getPriority() {
        return metadata.priority();
    }
}