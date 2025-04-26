package com.krickert.search.test.kafka;

import com.krickert.search.model.PipeStream;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Utility classes for working with PipeStream in Kafka tests.
 * These classes provide standard implementations for producing and consuming PipeStream messages.
 */
public class PipeStreamUtils {

    private static final Logger LOG = LoggerFactory.getLogger(PipeStreamUtils.class);

    /**
     * Kafka client for sending PipeStream messages.
     */
    @KafkaClient
    public interface PipeStreamProducer {
        /**
         * Send a PipeStream message to the input topic.
         *
         * @param message the message to send
         * @return a CompletableFuture that completes when the message is sent
         */
        @Topic("${kafka.topics.input:echo-input-test}")
        CompletableFuture<Void> sendToInputTopic(PipeStream message);

        /**
         * Send a PipeStream message to the output topic.
         *
         * @param message the message to send
         * @return a CompletableFuture that completes when the message is sent
         */
        @Topic("${kafka.topics.output:echo-output-test}")
        CompletableFuture<Void> sendToOutputTopic(PipeStream message);
    }

    /**
     * Kafka listener for receiving PipeStream messages.
     * This class provides methods to get received messages and wait for the next message.
     */
    @Singleton
    @KafkaListener(groupId = "pipe-stream-consumer-group")
    public static class PipeStreamConsumer {
        private static final Logger LOG = LoggerFactory.getLogger(PipeStreamConsumer.class);
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private CompletableFuture<PipeStream> nextMessage = new CompletableFuture<>();
        private String topic;

        /**
         * Set the topic to listen to.
         * This should be called before any messages are received.
         *
         * @param topic the topic to listen to
         */
        public void setTopic(String topic) {
            this.topic = topic;
            LOG.info("Set topic to: {}", topic);
        }

        /**
         * Receive a message from the configured topic.
         * The topic is configured in the AbstractMicronautKafkaTest.setup() method.
         *
         * @param message the received message
         * @param topic the topic the message was received from
         */
        public void receive(PipeStream message, String topic) {
            // Only process messages from the configured topic
            if (this.topic != null && this.topic.equals(topic)) {
                LOG.info("Received message on topic {}: {}", topic, message);
                synchronized (receivedMessages) {
                    receivedMessages.add(message);
                    nextMessage.complete(message);
                }
            }
        }

        /**
         * Receive a message from a specific topic.
         * This method is called by Kafka when a message is received on the specified topic.
         *
         * @param message the received message
         */
        @Topic("${kafka.topics.output:echo-output-test}")
        public void receiveFromOutputTopic(PipeStream message) {
            receive(message, topic);
        }

        /**
         * Get the next message received from the topic.
         *
         * @param timeoutSeconds the timeout in seconds
         * @return the next message
         * @throws Exception if an error occurs or the timeout is reached
         */
        public PipeStream getNextMessage(long timeoutSeconds) throws Exception {
            return nextMessage.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        /**
         * Get all received messages.
         *
         * @return a list of all received messages
         */
        public List<PipeStream> getReceivedMessages() {
            synchronized (receivedMessages) {
                return new ArrayList<>(receivedMessages);
            }
        }

        /**
         * Reset the consumer state.
         * This clears all received messages and resets the next message future.
         */
        public void reset() {
            synchronized (receivedMessages) {
                receivedMessages.clear();
                if (nextMessage.isDone()) {
                    nextMessage = new CompletableFuture<>();
                }
            }
        }
    }
}
