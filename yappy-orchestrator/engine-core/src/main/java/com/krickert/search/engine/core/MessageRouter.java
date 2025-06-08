package com.krickert.search.engine.core;

import com.krickert.search.model.PipeStream;
import com.krickert.search.sdk.ProcessResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Interface for routing messages between pipeline steps.
 * 
 * The MessageRouter determines where to send messages based on
 * pipeline configuration. It handles both gRPC and Kafka routing.
 */
public interface MessageRouter {
    
    /**
     * Route a processed message to its next destination(s).
     * 
     * This will:
     * 1. Clone the PipeStream for each output
     * 2. Update target_step_name for each clone
     * 3. Send to appropriate transport (gRPC or Kafka)
     * 
     * @param originalStream The original PipeStream that was processed
     * @param response The response from processing
     * @param nextSteps The configured next steps from pipeline config
     * @return Flux of routing results (one per destination)
     */
    Flux<RoutingResult> routeToNextSteps(
        PipeStream originalStream, 
        ProcessResponse response,
        List<String> nextSteps
    );
    
    /**
     * Route a message to a specific step via gRPC.
     * 
     * @param pipeStream The message to route
     * @param targetStep The target step name
     * @return Mono of the routing result
     */
    Mono<RoutingResult> routeViaGrpc(PipeStream pipeStream, String targetStep);
    
    /**
     * Route a message to a specific step via Kafka.
     * 
     * @param pipeStream The message to route
     * @param targetStep The target step name
     * @return Mono of the routing result
     */
    Mono<RoutingResult> routeViaKafka(PipeStream pipeStream, String targetStep);
    
    /**
     * Route a message to the dead letter queue.
     * 
     * @param pipeStream The failed message
     * @param stepName The step that failed
     * @param error The error that occurred
     * @return Mono indicating completion
     */
    Mono<Void> routeToDeadLetter(PipeStream pipeStream, String stepName, Throwable error);
}