package com.krickert.search.engine.core.routing;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Strategy for determining routing information for a PipeStream.
 * Implementations can use different approaches like configuration-based,
 * service discovery, or rule-based routing.
 */
public interface RoutingStrategy {
    
    /**
     * Determine the routing information for a given PipeStream.
     * 
     * @param pipeStream The message to route
     * @return Mono<RouteData> with the routing information
     */
    Mono<RouteData> determineRoute(PipeStream pipeStream);
}