package com.krickert.search.commons.events;

import com.krickert.search.model.PipeStream;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Event published when PipeStream processing is completed successfully.
 */
@Introspected
public class PipeStreamCompletedEvent extends BaseEvent {
    
    private final PipeStream originalPipeStream;
    private final PipeStream processedPipeStream;
    private final String processingModule;
    private final Instant startTime;
    private final Instant endTime;
    private final Map<String, Object> metrics;
    
    public PipeStreamCompletedEvent(
            @NonNull PipeStream originalPipeStream,
            @NonNull PipeStream processedPipeStream,
            @NonNull String processingModule,
            @NonNull Instant startTime,
            @NonNull Instant endTime,
            @NonNull EventMetadata metadata,
            @NonNull Map<String, Object> metrics) {
        super(metadata);
        this.originalPipeStream = originalPipeStream;
        this.processedPipeStream = processedPipeStream;
        this.processingModule = processingModule;
        this.startTime = startTime;
        this.endTime = endTime;
        this.metrics = metrics;
    }
    
    /**
     * Get the processing duration.
     */
    public Duration getProcessingDuration() {
        return Duration.between(startTime, endTime);
    }
    
    /**
     * Check if the PipeStream was modified during processing.
     */
    public boolean wasModified() {
        return !originalPipeStream.equals(processedPipeStream);
    }
    
    // Getters
    public PipeStream getOriginalPipeStream() {
        return originalPipeStream;
    }
    
    public PipeStream getProcessedPipeStream() {
        return processedPipeStream;
    }
    
    public String getProcessingModule() {
        return processingModule;
    }
    
    public Instant getStartTime() {
        return startTime;
    }
    
    public Instant getEndTime() {
        return endTime;
    }
    
    public Map<String, Object> getMetrics() {
        return metrics;
    }
}