package com.krickert.search.engine.core.routing;

import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Default implementation of Router that delegates to appropriate MessageForwarders
 * based on transport type.
 */
@Singleton
public class DefaultRouter implements Router {
    
    private static final Logger logger = LoggerFactory.getLogger(DefaultRouter.class);
    
    private final Map<RouteData.TransportType, MessageForwarder> forwarders;
    private final RoutingStrategy routingStrategy;
    
    @Inject
    public DefaultRouter(List<MessageForwarder> forwarderList, RoutingStrategy routingStrategy) {
        this.forwarders = forwarderList.stream()
                .collect(Collectors.toMap(
                        MessageForwarder::getTransportType,
                        forwarder -> forwarder,
                        (existing, replacement) -> {
                            logger.warn("Duplicate forwarder for transport type {}. Using the last one.", 
                                    replacement.getTransportType());
                            return replacement;
                        }
                ));
        this.routingStrategy = routingStrategy;
        
        logger.info("Initialized router with {} forwarders: {}", 
                forwarders.size(), 
                forwarders.keySet());
    }
    
    @Override
    public Mono<Void> route(PipeStream pipeStream, RouteData routeData) {
        logger.debug("Routing message {} via {} to {}", 
                pipeStream.getStreamId(), 
                routeData.transportType(),
                routeData.destinationService());
        
        MessageForwarder forwarder = forwarders.get(routeData.transportType());
        if (forwarder == null) {
            return Mono.error(new IllegalStateException(
                    "No forwarder available for transport type: " + routeData.transportType()));
        }
        
        return forwarder.forward(pipeStream, routeData)
                .doOnSuccess(v -> logger.debug("Successfully routed message {} to {}", 
                        pipeStream.getStreamId(), 
                        routeData.destinationService()))
                .doOnError(e -> logger.error("Failed to route message {} to {}: {}", 
                        pipeStream.getStreamId(), 
                        routeData.destinationService(), 
                        e.getMessage()));
    }
    
    @Override
    public Mono<Void> route(PipeStream pipeStream) {
        logger.debug("Determining route for message {} at step {}", 
                pipeStream.getStreamId(), 
                pipeStream.getTargetStepName());
        
        return routingStrategy.determineRoute(pipeStream)
                .flatMap(routeData -> route(pipeStream, routeData));
    }
}