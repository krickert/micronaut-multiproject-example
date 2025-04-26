package com.krickert.search.test;

import com.krickert.search.model.PipeStream;
import com.krickert.search.test.kafka.AbstractKafkaIntegrationTest;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Concrete implementation of AbstractKafkaIntegrationTest for testing Kafka integration with AWS Glue Schema Registry.
 * This class tests producing and consuming PipeStream messages with Kafka using the Moto Schema Registry.
 */
@MicronautTest(environments = "test", transactional = false)
public class KafkaGlueIntegrationTest extends AbstractKafkaIntegrationTest<PipeStream> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaGlueIntegrationTest.class);
    private static final String TOPIC = "test-PipeStream";
    private static final String SCHEMA_REGISTRY_TYPE_PROP = "schema.registry.type";

    @Inject
    PipeStreamProducer producer;

    @Inject
    TestPipeStreamConsumer consumer;

    @BeforeEach
    public void setupSchemaRegistryType() {
        // Set the system property to use Moto schema registry
        System.setProperty(SCHEMA_REGISTRY_TYPE_PROP, "moto");
        LOG.info("Set schema registry type to: moto");
    }

    @Override
    protected PipeStream createTestMessage() {
        return PipeDocExample.createFullPipeStream();
    }

    @Override
    protected MessageProducer<PipeStream> getProducer() {
        return producer::sendPipeStream;
    }

    @Override
    protected MessageConsumer<PipeStream> getConsumer() {
        return consumer;
    }

    // Producer client
    @KafkaClient
    public interface PipeStreamProducer {
        @Topic(TOPIC)
        CompletableFuture<Void> sendPipeStream(PipeStream PipeStream);
    }

    // Consumer implementation
    @KafkaListener(groupId = "test-group")
    public static class TestPipeStreamConsumer implements MessageConsumer<PipeStream> {
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private final CompletableFuture<PipeStream> nextMessage = new CompletableFuture<>();

        @Topic(TOPIC)
        void receive(PipeStream PipeStream) {
            LOG.info("Received message: {}", PipeStream);
            synchronized (receivedMessages) {
                receivedMessages.add(PipeStream);
                nextMessage.complete(PipeStream);
            }
        }

        @Override
        public PipeStream getNextMessage(long timeoutSeconds) throws Exception {
            return nextMessage.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public List<PipeStream> getReceivedMessages() {
            synchronized (receivedMessages) {
                return new ArrayList<>(receivedMessages);
            }
        }
    }
}