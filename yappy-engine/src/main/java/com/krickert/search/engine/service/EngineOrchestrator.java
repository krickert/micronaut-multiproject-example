package com.krickert.search.engine.service;

import reactor.core.publisher.Mono;

/**
 * Orchestrates the engine lifecycle and coordinates all services.
 * This is the main entry point for engine operations.
 */
public interface EngineOrchestrator {
    
    /**
     * Starts the engine and all dependent services.
     * @return Mono that completes when engine is fully started
     */
    Mono<Void> start();
    
    /**
     * Stops the engine and all dependent services gracefully.
     * @return Mono that completes when engine is fully stopped
     */
    Mono<Void> stop();
    
    /**
     * Checks if the engine is currently running.
     * @return true if engine is running, false otherwise
     */
    boolean isRunning();
    
    /**
     * Gets the current health status of the engine.
     * @return EngineHealthStatus containing detailed health information
     */
    EngineHealthStatus getHealthStatus();
}