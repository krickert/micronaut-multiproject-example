package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime status of a pipeline.
 */
@Serdeable
@Schema(description = "Pipeline runtime status and metrics")
public record PipelineStatus(
    @Schema(description = "Pipeline ID")
    String pipelineId,
    
    @Schema(description = "Current status", allowableValues = {"active", "inactive", "error"})
    String status,
    
    @Schema(description = "Last time data was processed")
    Instant lastProcessed,
    
    @Schema(description = "Total documents processed")
    long documentsProcessed,
    
    @Schema(description = "Average processing time in milliseconds")
    long averageProcessingTimeMs,
    
    @Schema(description = "Current error count")
    long errorCount,
    
    @Schema(description = "Status of each step")
    List<StepStatus> stepStatuses,
    
    @Schema(description = "Additional metrics")
    Map<String, Object> metrics
) {
    
    @Serdeable
    @Schema(description = "Status of a pipeline step")
    public record StepStatus(
        @Schema(description = "Step ID")
        String stepId,
        
        @Schema(description = "Module handling this step")
        String module,
        
        @Schema(description = "Step health", allowableValues = {"healthy", "degraded", "unhealthy"})
        String health,
        
        @Schema(description = "Number of available instances")
        int availableInstances,
        
        @Schema(description = "Documents processed by this step")
        long documentsProcessed,
        
        @Schema(description = "Errors in this step")
        long errors,
        
        @Schema(description = "Average latency in milliseconds")
        long averageLatencyMs
    ) {}
}