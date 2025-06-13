package com.krickert.search.engine.core.routing;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Routes PipeStream messages to their next destination.
 * The router determines the appropriate transport mechanism and
 * delegates to the corresponding MessageForwarder.
 */
public interface Router {
    
    /**
     * Route a PipeStream to its next destination based on the routing data.
     * 
     * @param pipeStream The message to route
     * @param routeData The routing information
     * @return Mono<Void> that completes when the message has been sent
     */
    Mono<Void> route(PipeStream pipeStream, RouteData routeData);
    
    /**
     * Route a PipeStream using routing information derived from pipeline configuration.
     * This method will determine the RouteData based on the current step and pipeline config.
     * 
     * @param pipeStream The message to route
     * @return Mono<Void> that completes when the message has been sent
     */
    Mono<Void> route(PipeStream pipeStream);
}