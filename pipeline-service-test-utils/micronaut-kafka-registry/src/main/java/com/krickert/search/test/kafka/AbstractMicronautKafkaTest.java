package com.krickert.search.test.kafka;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Abstract test class for testing Kafka integration with a schema registry using Micronaut's Kafka annotations.
 * This class provides a framework for testing producing and consuming messages with Kafka
 * using Micronaut's built-in Kafka client and listener annotations.
 * 
 * @param <T> the type of message to produce and consume
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@MicronautTest(environments = "test", transactional = false)
public abstract class AbstractMicronautKafkaTest<T> extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(AbstractMicronautKafkaTest.class);
    
    /**
     * Get the Kafka topic name to use for the test.
     * 
     * @return the topic name
     */
    protected abstract String getTopicName();
    
    /**
     * Create a test message to use for the integration test.
     * 
     * @return a test message
     */
    protected abstract T createTestMessage();
    
    /**
     * Send a message to the Kafka topic.
     * This method should use a Micronaut @KafkaClient to send the message.
     * 
     * @param message the message to send
     * @throws Exception if an error occurs
     */
    protected abstract void sendMessage(T message) throws Exception;
    
    /**
     * Get the next message received from the Kafka topic.
     * This method should retrieve a message from a Micronaut @KafkaListener.
     * 
     * @param timeoutSeconds the timeout in seconds
     * @return the received message
     * @throws Exception if an error occurs
     */
    protected abstract T getNextMessage(long timeoutSeconds) throws Exception;
    
    /**
     * Test producing and consuming a message with Kafka using Micronaut's Kafka annotations.
     */
    @Test
    void testProduceAndConsumeMessage() throws Exception {
        // Create test message
        T testMessage = createTestMessage();
        
        // Produce the message using Micronaut's @KafkaClient
        sendMessage(testMessage);
        log.info("Produced test message: {}", testMessage);
        
        // Wait for the consumer to receive the message using Micronaut's @KafkaListener
        T receivedMessage = getNextMessage(10);
        log.info("Received message: {}", receivedMessage);
        
        // Verify the received message
        assertNotNull(receivedMessage, "Should have received a message");
        assertEquals(testMessage, receivedMessage, "Received message should match sent message");
    }
}