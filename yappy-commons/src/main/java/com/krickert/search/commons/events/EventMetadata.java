package com.krickert.search.commons.events;

import io.micronaut.core.annotation.Introspected;

import java.util.Map;

/**
 * Common metadata for all events in the system.
 */
@Introspected
public record EventMetadata(
    String correlationId,
    String sourceModule,
    String sourceVersion,
    Map<String, String> headers,
    EventPriority priority
) {
    
    /**
     * Create metadata with default priority (NORMAL).
     */
    public static EventMetadata create(String correlationId, String sourceModule, String sourceVersion) {
        return new EventMetadata(
            correlationId,
            sourceModule,
            sourceVersion,
            Map.of(),
            EventPriority.NORMAL
        );
    }
    
    /**
     * Create metadata with headers.
     */
    public static EventMetadata createWithHeaders(
            String correlationId, 
            String sourceModule, 
            String sourceVersion,
            Map<String, String> headers) {
        return new EventMetadata(
            correlationId,
            sourceModule,
            sourceVersion,
            headers,
            EventPriority.NORMAL
        );
    }
}