// KafkaForwarderClient.java
package com.krickert.search.pipeline.engine.kafka;

import com.krickert.search.model.PipeStream;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaKey;
import io.micronaut.configuration.kafka.annotation.Topic;
import org.apache.kafka.clients.producer.RecordMetadata; // Import this

import java.util.UUID;
import java.util.concurrent.CompletableFuture; // Import this

@KafkaClient(id = "pipestream-forwarder") // Assign an ID for specific configurations
@io.micronaut.context.annotation.Requires(property = "kafka.enabled", value = "true")
public interface KafkaForwarderClient {
    /**
     * Sends a PipeStream message to the specified Kafka topic.
     *
     * @param topic The target Kafka topic.
     * @param key The key for the Kafka message.
     * @param pipe The PipeStream payload.
     * @return A CompletableFuture that will be completed with RecordMetadata upon successful send,
     *         or completed exceptionally if the send fails.
     */
    CompletableFuture<RecordMetadata> send(@Topic String topic, @KafkaKey UUID key, PipeStream pipe);
}