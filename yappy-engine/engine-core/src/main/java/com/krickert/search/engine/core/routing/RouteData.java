package com.krickert.search.engine.core.routing;

/**
 * Immutable data structure containing routing information for a pipeline step.
 * This tells the engine where to send the message next.
 */
public record RouteData(
        String targetPipelineName,   // The pipeline to execute
        String targetStepName,       // The specific step within that pipeline
        String destinationService,   // The service name (for service discovery)
        TransportType transportType, // How to send it (GRPC, KAFKA, etc.)
        String streamId             // The stream identifier for tracking
) {
    public RouteData {
        // Validate required fields
        if (targetStepName == null || targetStepName.isBlank()) {
            throw new IllegalArgumentException("targetStepName cannot be null or blank");
        }
        if (destinationService == null || destinationService.isBlank()) {
            throw new IllegalArgumentException("destinationService cannot be null or blank");
        }
        if (transportType == null) {
            throw new IllegalArgumentException("transportType cannot be null");
        }
        if (streamId == null || streamId.isBlank()) {
            throw new IllegalArgumentException("streamId cannot be null or blank");
        }
        // targetPipelineName can be null for steps within the same pipeline
    }
    
    /**
     * Transport types supported by the routing system.
     */
    public enum TransportType {
        GRPC,
        KAFKA,
        INTERNAL  // For in-process calls
    }
}