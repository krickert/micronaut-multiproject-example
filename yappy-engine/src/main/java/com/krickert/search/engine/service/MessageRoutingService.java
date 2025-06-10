package com.krickert.search.engine.service;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Service responsible for routing messages through the pipeline.
 * It determines which module should process a message next based on
 * the pipeline configuration and current processing state.
 */
public interface MessageRoutingService {
    
    /**
     * Route a message to the next appropriate processor in the pipeline.
     * 
     * @param pipeStream The message to route
     * @param pipelineName The name of the pipeline being executed
     * @return The processed message from the next module
     */
    Mono<PipeStream> routeMessage(PipeStream pipeStream, String pipelineName);
    
    /**
     * Send a message to a specific module for processing.
     * 
     * @param pipeStream The message to send
     * @param moduleId The ID of the module to send to
     * @return The processed message
     */
    Mono<PipeStream> sendToModule(PipeStream pipeStream, String moduleId);
    
    /**
     * Send a message to a Kafka topic (for inter-module communication or output).
     * 
     * @param pipeStream The message to send
     * @param topicName The Kafka topic name
     * @return Success status
     */
    Mono<Boolean> sendToKafkaTopic(PipeStream pipeStream, String topicName);
}