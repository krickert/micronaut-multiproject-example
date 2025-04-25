package com.krickert.search.test;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.test.kafka.AbstractKafkaIntegrationTest;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Concrete implementation of AbstractKafkaIntegrationTest for testing Kafka integration with AWS Glue Schema Registry.
 * This class tests producing and consuming PipeDoc messages with Kafka using the Moto Schema Registry.
 */
@MicronautTest(environments = "test", transactional = false)
public class KafkaGlueIntegrationTest extends AbstractKafkaIntegrationTest<PipeDoc> {
    private static final Logger LOG = LoggerFactory.getLogger(KafkaGlueIntegrationTest.class);
    private static final String TOPIC = "test-pipedoc";

    @Inject
    PipeDocProducer producer;

    @Inject
    TestPipeDocConsumer consumer;

    @Override
    protected PipeDoc createTestMessage() {
        return PipeDocExample.createFullPipeDoc();
    }

    @Override
    protected MessageProducer<PipeDoc> getProducer() {
        return producer::sendPipeDoc;
    }

    @Override
    protected MessageConsumer<PipeDoc> getConsumer() {
        return consumer;
    }

    // Producer client
    @KafkaClient
    public interface PipeDocProducer {
        @Topic(TOPIC)
        CompletableFuture<Void> sendPipeDoc(PipeDoc pipeDoc);
    }

    // Consumer implementation
    @KafkaListener(groupId = "test-group")
    public static class TestPipeDocConsumer implements MessageConsumer<PipeDoc> {
        private final List<PipeDoc> receivedMessages = new ArrayList<>();
        private final CompletableFuture<PipeDoc> nextMessage = new CompletableFuture<>();

        @Topic(TOPIC)
        void receive(PipeDoc pipeDoc) {
            LOG.info("Received message: {}", pipeDoc);
            synchronized (receivedMessages) {
                receivedMessages.add(pipeDoc);
                nextMessage.complete(pipeDoc);
            }
        }

        @Override
        public PipeDoc getNextMessage(long timeoutSeconds) throws Exception {
            return nextMessage.get(timeoutSeconds, TimeUnit.SECONDS);
        }

        @Override
        public List<PipeDoc> getReceivedMessages() {
            synchronized (receivedMessages) {
                return new ArrayList<>(receivedMessages);
            }
        }
    }
}
