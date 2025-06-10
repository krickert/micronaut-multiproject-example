package com.krickert.search.engine.core.transport.kafka;

import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Stub implementation for Kafka message forwarding.
 * TODO: Implement actual Kafka integration when ready.
 */
@Singleton
public class KafkaMessageForwarder implements MessageForwarder {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaMessageForwarder.class);
    
    @Override
    public Mono<Void> forward(PipeStream pipeStream, RouteData routeData) {
        // Stub implementation - just log for now
        logger.info("STUB: Would forward message {} to Kafka topic {} for step {}", 
            pipeStream.getStreamId(), 
            routeData.destinationService(),
            routeData.targetStepName());
        
        return Mono.empty();
    }
    
    @Override
    public boolean canHandle(RouteData.TransportType transportType) {
        return transportType == RouteData.TransportType.KAFKA;
    }
    
    @Override
    public RouteData.TransportType getTransportType() {
        return RouteData.TransportType.KAFKA;
    }
}