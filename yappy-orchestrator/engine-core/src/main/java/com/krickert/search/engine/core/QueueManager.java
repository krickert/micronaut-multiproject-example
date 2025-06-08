package com.krickert.search.engine.core;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Interface for managing in-memory queues for pipeline steps.
 * 
 * This is used in gRPC-only mode to provide buffering and
 * backpressure between pipeline steps.
 */
public interface QueueManager {
    
    /**
     * Add a message to a step's queue.
     * 
     * This will block if the queue is full, providing natural backpressure.
     * 
     * @param stepName The target step
     * @param message The message to queue
     * @param timeoutMillis Maximum time to wait for queue space
     * @return Mono indicating success or timeout
     */
    Mono<Boolean> offer(String stepName, PipeStream message, long timeoutMillis);
    
    /**
     * Poll for a message from a step's queue.
     * 
     * This is used by worker threads to get messages to process.
     * 
     * @param stepName The step to poll from
     * @param timeoutMillis Maximum time to wait for a message
     * @return Mono with the message, or empty if timeout
     */
    Mono<PipeStream> poll(String stepName, long timeoutMillis);
    
    /**
     * Get the current size of a step's queue.
     * 
     * @param stepName The step name
     * @return Current queue size
     */
    int getQueueSize(String stepName);
    
    /**
     * Get the configured capacity for a step's queue.
     * 
     * @param stepName The step name
     * @return Queue capacity
     */
    int getQueueCapacity(String stepName);
    
    /**
     * Check if a queue is full.
     * 
     * @param stepName The step name
     * @return true if the queue is at capacity
     */
    boolean isQueueFull(String stepName);
    
    /**
     * Clear all messages from a queue.
     * 
     * Used during shutdown or error recovery.
     * 
     * @param stepName The step name
     */
    void clearQueue(String stepName);
    
    /**
     * Shutdown all queues and release resources.
     */
    void shutdown();
}