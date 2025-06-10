package com.krickert.search.engine.core.transport.internal;

import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Stub implementation for internal message forwarding.
 * For in-process routing between pipeline steps.
 * TODO: Implement actual internal step execution when ready.
 */
@Singleton
public class InternalMessageForwarder implements MessageForwarder {
    
    private static final Logger logger = LoggerFactory.getLogger(InternalMessageForwarder.class);
    
    @Override
    public Mono<Void> forward(PipeStream pipeStream, RouteData routeData) {
        // Stub implementation - just log for now
        logger.info("STUB: Would forward message {} internally to step {} in pipeline {}", 
            pipeStream.getStreamId(), 
            routeData.targetStepName(),
            routeData.targetPipelineName() != null ? routeData.targetPipelineName() : "current");
        
        return Mono.empty();
    }
    
    @Override
    public boolean canHandle(RouteData.TransportType transportType) {
        return transportType == RouteData.TransportType.INTERNAL;
    }
    
    @Override
    public RouteData.TransportType getTransportType() {
        return RouteData.TransportType.INTERNAL;
    }
}