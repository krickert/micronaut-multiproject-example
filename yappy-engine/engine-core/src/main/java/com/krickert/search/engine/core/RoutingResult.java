package com.krickert.search.engine.core;

import com.krickert.search.config.pipeline.model.TransportType;
import java.time.Instant;
import java.util.Optional;

/**
 * Result of a routing operation.
 * 
 * Captures whether the routing was successful and any relevant metadata.
 */
public record RoutingResult(
    String targetStep,
    TransportType transportType,
    boolean success,
    Optional<String> errorMessage,
    Instant timestamp,
    long durationMillis
) {
    
    /**
     * Create a successful routing result.
     */
    public static RoutingResult success(String targetStep, TransportType transportType, long durationMillis) {
        return new RoutingResult(
            targetStep,
            transportType,
            true,
            Optional.empty(),
            Instant.now(),
            durationMillis
        );
    }
    
    /**
     * Create a failed routing result.
     */
    public static RoutingResult failure(String targetStep, TransportType transportType, String errorMessage, long durationMillis) {
        return new RoutingResult(
            targetStep,
            transportType,
            false,
            Optional.of(errorMessage),
            Instant.now(),
            durationMillis
        );
    }
}