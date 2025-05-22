package com.krickert.search.pipeline.engine;

import com.krickert.search.model.PipeStream;

/**
 * Interface for processing PipeStream objects.
 * 
 * This interface defines the contract for components that can process
 * PipeStream objects, such as the PipeStreamEngineImpl which implements
 * the gRPC service for processing PipeStreams.
 * 
 * Implementations of this interface are responsible for:
 * 1. Processing PipeStream objects
 * 2. Routing them to the appropriate next step
 * 3. Handling errors and exceptions
 */
public interface PipeStreamEngine {
    
    /**
     * Processes a PipeStream object.
     * 
     * This method is responsible for:
     * 1. Determining the target step for the PipeStream
     * 2. Executing the step
     * 3. Routing the result to the next step(s)
     * 
     * @param pipeStream The PipeStream to process
     */
    void processStream(PipeStream pipeStream);
}