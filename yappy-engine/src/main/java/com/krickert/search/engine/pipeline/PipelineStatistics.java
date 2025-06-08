package com.krickert.search.engine.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Statistics for pipeline execution.
 */
public record PipelineStatistics(
    String pipelineId,
    long documentsProcessed,
    long documentsSucceeded,
    long documentsFailed,
    Duration averageProcessingTime,
    Duration minProcessingTime,
    Duration maxProcessingTime,
    Instant lastProcessedTime,
    Map<String, StepStatistics> stepStatistics
) {
    
    public record StepStatistics(
        String stepName,
        long invocations,
        long successes,
        long failures,
        Duration averageExecutionTime
    ) {}
}