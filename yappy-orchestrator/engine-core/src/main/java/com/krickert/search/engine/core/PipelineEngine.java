package com.krickert.search.engine.core;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Core interface for the pipeline orchestration engine.
 * 
 * The PipelineEngine is responsible for routing messages through
 * configured pipeline steps. It contains no business logic, only
 * orchestration logic.
 */
public interface PipelineEngine {
    
    /**
     * Process a PipeStream through the pipeline.
     * 
     * The engine will:
     * 1. Look up the target step configuration
     * 2. Route to the appropriate module via gRPC (or Kafka)
     * 3. Handle the response and route to next steps
     * 
     * @param pipeStream The message to process
     * @return Mono indicating completion or error
     */
    Mono<Void> processMessage(PipeStream pipeStream);
    
    /**
     * Start the pipeline engine.
     * This may initialize worker threads, connections, etc.
     * 
     * @return Mono indicating successful startup
     */
    Mono<Void> start();
    
    /**
     * Stop the pipeline engine gracefully.
     * This should complete in-flight messages and clean up resources.
     * 
     * @return Mono indicating successful shutdown
     */
    Mono<Void> stop();
    
    /**
     * Check if the engine is currently running.
     * 
     * @return true if the engine is operational
     */
    boolean isRunning();
}