// KafkaForwarder.java
package com.krickert.search.orchestrator.kafka.producer;

import com.krickert.search.model.PipeStream;
import com.krickert.search.model.ProtobufUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
@io.micronaut.context.annotation.Requires(property = "kafka.enabled", value = "true")
public class KafkaForwarder {

    private static final Logger log = LoggerFactory.getLogger(KafkaForwarder.class);
    private final KafkaForwarderClient kafkaForwarderClient;

    // Consider making this prefix configurable via application properties
    private static final String DEFAULT_ERROR_TOPIC_PREFIX = "error-";

    @Inject // Good practice to use @Inject on constructor
    public KafkaForwarder(KafkaForwarderClient kafkaForwarderClient) {
        log.info("Creating KafkaForwarder instance");
        this.kafkaForwarderClient = checkNotNull(kafkaForwarderClient, "kafkaForwarderClient cannot be null");
        log.info("KafkaForwarder instance created successfully");
    }

    /**
     * Asynchronously forwards a PipeStream to the specified Kafka topic.
     * Logs the outcome of the send operation.
     *
     * @param pipe The PipeStream to send. Must not be null.
     * @param topic The target Kafka topic. Must not be null or blank.
     * @return A CompletableFuture<RecordMetadata> representing the asynchronous send operation.
     *         The caller can use this future to implement further actions based on send success/failure.
     */
    public CompletableFuture<RecordMetadata> forwardToKafka(PipeStream pipe, String topic) {
        checkNotNull(pipe, "PipeStream cannot be null for Kafka forwarding.");
        checkNotNull(pipe.getStreamId(), "PipeStream streamId cannot be null.");
        checkNotNull(topic, "Topic cannot be null or blank for Kafka forwarding.");
        if (topic.isBlank()) {
            throw new IllegalArgumentException("Topic cannot be blank for Kafka forwarding.");
        }


        UUID key = ProtobufUtils.createKey(pipe.getStreamId()); // Assuming this handles null streamId if that's possible
        log.debug("Attempting to forward PipeStream (streamId: {}) to Kafka topic: {}", pipe.getStreamId(), topic);

        CompletableFuture<RecordMetadata> sendFuture = kafkaForwarderClient.send(topic, key, pipe);

        // Handle the completion of the future for logging and potential basic error handling.
        // The caller (e.g., PipeStreamEngineImpl) can also chain its own logic to this future.
        sendFuture.whenComplete((metadata, ex) -> {
            if (ex != null) {
                log.error("Failed to send PipeStream (streamId: {}) to topic '{}': {}",
                        pipe.getStreamId(), topic, ex.getMessage(), ex);
                // COMMENT: This is where you'd consider logic if Kafka is down.
                // For example:
                // 1. Retry logic (though Kafka client itself has retries, this could be an application-level retry).
                // 2. Persisting the message to a local dead-letter store (e.g., a database table, local file)
                //    for later reprocessing if Kafka is confirmed to be down for an extended period.
                // 3. Triggering alerts to an operations team.
                // The exact strategy depends on your system's reliability requirements.
            } else {
                log.info("Successfully sent PipeStream (streamId: {}) to topic '{}' - Partition: {}, Offset: {}",
                        pipe.getStreamId(), topic, metadata.partition(), metadata.offset());
            }
        });

        return sendFuture;
    }

    /**
     * Asynchronously forwards a PipeStream to a designated error topic.
     * The error topic name is derived by prefixing the original topic.
     *
     * @param pipe The PipeStream that encountered an error. Must not be null.
     * @param originalTopic The topic where the message was originally intended or processed. Must not be null or blank.
     * @return A CompletableFuture<RecordMetadata> representing the asynchronous send operation to the error topic.
     */
    public CompletableFuture<RecordMetadata> forwardToErrorTopic(PipeStream pipe, String originalTopic) {
        checkNotNull(pipe, "PipeStream cannot be null for error topic forwarding.");
        checkNotNull(pipe.getStreamId(), "PipeStream streamId cannot be null for error topic forwarding.");
        checkNotNull(originalTopic, "Original topic cannot be null or blank for error topic forwarding.");
        if (originalTopic.isBlank()) {
            throw new IllegalArgumentException("Original topic cannot be blank for error topic forwarding.");
        }

        String errorTopic = DEFAULT_ERROR_TOPIC_PREFIX + originalTopic;
        UUID key = ProtobufUtils.createKey(pipe.getStreamId());
        log.warn("Attempting to forward erroneous PipeStream (streamId: {}) to error topic: {}", pipe.getStreamId(), errorTopic);

        CompletableFuture<RecordMetadata> sendFuture = kafkaForwarderClient.send(errorTopic, key, pipe);

        sendFuture.whenComplete((metadata, ex) -> {
            if (ex != null) {
                log.error("CRITICAL: Failed to send PipeStream (streamId: {}) to error topic '{}': {}. " +
                                "This indicates a problem with the error handling path itself.",
                        pipe.getStreamId(), errorTopic, ex.getMessage(), ex);
                // If sending to the error topic fails, you have a more severe problem.
                // This might require alerting or a different fallback.
            } else {
                log.info("Successfully sent PipeStream (streamId: {}) to error topic '{}' - Partition: {}, Offset: {}",
                        pipe.getStreamId(), errorTopic, metadata.partition(), metadata.offset());
            }
        });
        return sendFuture;
    }
}