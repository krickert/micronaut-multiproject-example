package com.krickert.search.engine.core.transport.kafka;

import com.krickert.search.engine.core.routing.RouteData;
import com.krickert.search.engine.core.transport.MessageForwarder;
import com.krickert.search.model.PipeStream;
import com.krickert.search.orchestrator.kafka.producer.KafkaForwarder;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import java.util.Optional;

/**
 * Kafka message forwarder that integrates with the yappy-engine engine-kafka module.
 * This implementation uses the KafkaForwarder from the engine-kafka module to
 * send PipeStream messages to Kafka topics.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class KafkaMessageForwarder implements MessageForwarder {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaMessageForwarder.class);
    
    private final KafkaForwarder kafkaForwarder;
    
    @Inject
    public KafkaMessageForwarder(KafkaForwarder kafkaForwarder) {
        this.kafkaForwarder = kafkaForwarder;
        logger.info("KafkaMessageForwarder initialized with engine-kafka integration");
    }
    
    @Override
    public Mono<Optional<PipeStream>> forward(PipeStream pipeStream, RouteData routeData) {
        logger.debug("Forwarding message {} to Kafka topic {} for step {}", 
            pipeStream.getStreamId(), 
            routeData.destinationService(),
            routeData.targetStepName());
        
        // Use the engine-kafka KafkaForwarder to send the message
        return kafkaForwarder.forwardToKafka(pipeStream, routeData.destinationService())
            .doOnSuccess(v -> logger.debug("Successfully forwarded message {} to topic {}", 
                pipeStream.getStreamId(), routeData.destinationService()))
            .doOnError(e -> logger.error("Failed to forward message {} to topic {}: {}", 
                pipeStream.getStreamId(), routeData.destinationService(), e.getMessage(), e))
            .then(Mono.just(Optional.empty())); // Kafka is async, no immediate response
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