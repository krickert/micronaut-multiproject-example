package com.krickert.search.test;

import com.krickert.search.model.PipeStream;
import com.krickert.search.test.apicurio.ApicurioSchemaRegistry;
import com.krickert.search.test.kafka.AbstractMicronautKafkaTest;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Example implementation of AbstractMicronautKafkaTest for testing Kafka integration with Apicurio Registry.
 * This class demonstrates how to use Micronaut's Kafka annotations for producing and consuming messages.
 */
@MicronautTest(environments = "test", transactional = false)
public class MicronautKafkaApicurioTest extends AbstractMicronautKafkaTest<PipeStream> {
    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaApicurioTest.class);
    private static final String TOPIC = "test-micronaut-pipestream-apicurio";
    private static final String SCHEMA_REGISTRY_TYPE_PROP = "schema.registry.type";

    @Inject
    PipeStreamClient pipeStreamClient;

    @Inject
    PipeStreamListener pipeStreamListener;

    @Inject
    private ApicurioSchemaRegistry apicurioSchemaRegistry;

    @BeforeEach
    public void setup() {
        // Set the system property to use Apicurio schema registry
        System.setProperty(SCHEMA_REGISTRY_TYPE_PROP, "apicurio");
        LOG.info("Set schema registry type to: apicurio");

        // Set the return class to PipeStream for this test
        if (apicurioSchemaRegistry != null) {
            apicurioSchemaRegistry.setReturnClass(PipeStream.class.getName());
            LOG.info("Set return class to: {}", PipeStream.class.getName());
        } else {
            LOG.warn("ApicurioSchemaRegistry not injected, cannot set return class");
        }
        
        // Reset the listener for each test
        pipeStreamListener.reset();
    }

    @Override
    protected String getTopicName() {
        return TOPIC;
    }

    @Override
    protected PipeStream createTestMessage() {
        return PipeDocExample.createFullPipeStream();
    }

    @Override
    protected void sendMessage(PipeStream message) throws Exception {
        pipeStreamClient.send(message).get(10, TimeUnit.SECONDS);
    }

    @Override
    protected PipeStream getNextMessage(long timeoutSeconds) throws Exception {
        return pipeStreamListener.getNextMessage(timeoutSeconds);
    }

    /**
     * Kafka client for sending PipeStream messages.
     * Uses Micronaut's @KafkaClient annotation.
     */
    @KafkaClient
    public interface PipeStreamClient {
        @Topic(TOPIC)
        CompletableFuture<Void> send(PipeStream message);
    }

    /**
     * Kafka listener for receiving PipeStream messages.
     * Uses Micronaut's @KafkaListener annotation.
     */
    @Singleton
    @KafkaListener(groupId = "test-micronaut-group")
    public static class PipeStreamListener {
        private static final Logger LOG = LoggerFactory.getLogger(PipeStreamListener.class);
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private CompletableFuture<PipeStream> nextMessage = new CompletableFuture<>();

        @Topic(TOPIC)
        public void receive(PipeStream message) {
            LOG.info("Received message: {}", message);
            synchronized (receivedMessages) {
                receivedMessages.add(message);
                nextMessage.complete(message);
            }
        }

        public PipeStream getNextMessage(long timeoutSeconds) throws Exception {
            return nextMessage.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        public List<PipeStream> getReceivedMessages() {
            synchronized (receivedMessages) {
                return new ArrayList<>(receivedMessages);
            }
        }
        
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