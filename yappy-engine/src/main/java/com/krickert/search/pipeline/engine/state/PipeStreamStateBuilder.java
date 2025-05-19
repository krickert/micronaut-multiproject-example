package com.krickert.search.pipeline.engine.state;

import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.engine.common.RouteData;

import java.util.List;

/**
 * Builder for managing pipeline stream state during processing.
 */
public interface PipeStreamStateBuilder {
    /**
     * Get the original request state.
     * 
     * @return The immutable request state
     */
    PipeStream getRequestState();

    /**
     * Get the current present state.
     * 
     * @return The mutable present state
     */
    PipeStream.Builder getPresentState();

    /**
     * Update the hop number in the present state.
     * 
     * @param hopNumber The new hop number
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withHopNumber(int hopNumber);

    /**
     * Set the target step in the present state.
     * 
     * @param stepName The target step name
     * @return This builder for chaining
     */
    PipeStreamStateBuilder withTargetStep(String stepName);

    /**
     * Add a log entry to the present state.
     * 
     * @param log The log entry to add
     * @return This builder for chaining
     */
    PipeStreamStateBuilder addLogEntry(String log);

    /**
     * Calculate the next routes for the current state.
     * 
     * @return A list of route data for the next steps
     */
    List<RouteData> calculateNextRoutes();

    /**
     * Build the final response state.
     * 
     * @return The immutable response state
     */
    PipeStream build();
}