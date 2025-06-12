package com.krickert.search.orchestrator.kafka;

import com.krickert.search.model.PipeStream;
import reactor.core.publisher.Mono;

/**
 * Service for forwarding messages to Kafka topics.
 */
public interface KafkaForwardingService {

    /**
     * Forward a PipeStream message to a Kafka topic.
     * 
     * @param topic the Kafka topic to send the message to
     * @param pipeStream the message to send
     * @return Mono that completes when message is sent
     */
    Mono<Void> forwardToTopic(String topic, PipeStream pipeStream);

    /**
     * Forward a PipeStream message to a Kafka topic with a specific partition key.
     * 
     * @param topic the Kafka topic to send the message to
     * @param partitionKey the key to use for partitioning
     * @param pipeStream the message to send
     * @return Mono that completes when message is sent
     */
    Mono<Void> forwardToTopic(String topic, String partitionKey, PipeStream pipeStream);

    /**
     * Check if the forwarding service is healthy and can send messages.
     * 
     * @return Mono that completes with true if healthy, false otherwise
     */
    Mono<Boolean> isHealthy();
}