package com.krickert.search.test.kafka;

import com.krickert.search.test.registry.SchemaRegistry;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Abstract test class for testing Kafka integration with a schema registry.
 * This class provides a framework for testing producing and consuming messages with Kafka
 * using any schema registry implementation.
 * 
 * @param <T> the type of message to produce and consume
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test", transactional = false)
public abstract class AbstractKafkaIntegrationTest<T> extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractKafkaIntegrationTest.class);
    private static final String TOPIC = "test-message";

    /**
     * Create a test message to use for the integration test.
     * 
     * @return a test message
     */
    protected abstract T createTestMessage();

    /**
     * Get the producer for sending messages.
     * 
     * @return the producer
     */
    protected abstract MessageProducer<T> getProducer();

    /**
     * Get the consumer for receiving messages.
     * 
     * @return the consumer
     */
    protected abstract MessageConsumer<T> getConsumer();

    /**
     * Test producing and consuming a message with Kafka.
     */
    @Test
    void testProduceAndConsumeMessage() throws Exception {
        // Create test message
        T testMessage = createTestMessage();

        // Produce the message
        getProducer().sendMessage(testMessage).get(10, TimeUnit.SECONDS);
        log.info("Produced test message: {}", testMessage);

        // Wait for the consumer to receive the message
        T receivedMessage = getConsumer().getNextMessage(10);
        log.info("Received message: {}", receivedMessage);

        // Verify the received message
        assertNotNull(receivedMessage, "Should have received a message");
        assertEquals(testMessage, receivedMessage, "Received message should match sent message");
    }

    /**
     * Interface for a message producer.
     * 
     * @param <T> the type of message to produce
     */
    public interface MessageProducer<T> {
        /**
         * Send a message to Kafka.
         * 
         * @param message the message to send
         * @return a CompletableFuture that completes when the message is sent
         */
        CompletableFuture<Void> sendMessage(T message);
    }

    /**
     * Interface for a message consumer.
     * 
     * @param <T> the type of message to consume
     */
    public interface MessageConsumer<T> {
        /**
         * Get the next message received from Kafka.
         * 
         * @param timeoutSeconds the timeout in seconds
         * @return the next message
         * @throws Exception if an error occurs
         */
        T getNextMessage(long timeoutSeconds) throws Exception;

        /**
         * Get all received messages.
         * 
         * @return a list of all received messages
         */
        List<T> getReceivedMessages();
    }
}