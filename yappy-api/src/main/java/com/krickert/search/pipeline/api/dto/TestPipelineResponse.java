package com.krickert.search.pipeline.api.dto;

import io.micronaut.serde.annotation.Serdeable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Response from testing a pipeline.
 */
@Serdeable
@Schema(description = "Pipeline test results")
public record TestPipelineResponse(
    @Schema(description = "Whether the test completed successfully")
    boolean success,
    
    @Schema(description = "Total processing time")
    Duration processingTime,
    
    @Schema(description = "Results from each step")
    List<StepResult> stepResults,
    
    @Schema(description = "Any errors encountered")
    List<String> errors,
    
    @Schema(description = "Final output data")
    Map<String, Object> output
) {
    
    @Serdeable
    @Schema(description = "Result from a single pipeline step")
    public record StepResult(
        @Schema(description = "Step ID", example = "extract-text")
        String stepId,
        
        @Schema(description = "Module that processed this step")
        String module,
        
        @Schema(description = "Processing duration for this step")
        Duration duration,
        
        @Schema(description = "Whether this step succeeded")
        boolean success,
        
        @Schema(description = "Output from this step")
        Map<String, Object> output,
        
        @Schema(description = "Any error from this step")
        String error
    ) {}
}