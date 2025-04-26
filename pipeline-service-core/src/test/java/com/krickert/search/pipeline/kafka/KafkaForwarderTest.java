package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.*;
import com.krickert.search.test.kafka.AbstractKafkaTest;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "test", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaForwarderTest extends AbstractKafkaTest {
    private static final Logger log = LoggerFactory.getLogger(KafkaForwarderTest.class);
    private static final String TEST_TOPIC = "test-topic";
    private static final String BACKUP_TOPIC = "backup-test-topic";

    @Inject
    private KafkaForwarder kafkaForwarder;

    @Inject
    private TestListener testListener;

    @BeforeEach
    void setUp() {
        // Clear any messages from previous tests
        testListener.reset();
        log.info("Test setup complete");
    }

    @Test
    void testForwardToKafka() throws Exception {
        // Create test data
        String docId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Test Document")
                .setBody("This is a test document for Kafka forwarding")
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                .build();

        PipeStream pipeStream = PipeStream.newBuilder()
                .setRequest(request)
                .build();

        Route route = Route.newBuilder()
                .setDestination(TEST_TOPIC)
                .build();

        log.info("Forwarding test message to Kafka topic: {}", TEST_TOPIC);

        // Forward the message to Kafka
        kafkaForwarder.forwardToKafka(pipeStream, route);

        // Wait for the message to be received by the listener
        assertTrue(testListener.getLatch().await(30, TimeUnit.SECONDS), 
                "Timed out waiting for message to be received");

        // Verify the message was received
        List<PipeStream> receivedMessages = testListener.getReceivedMessages();
        assertEquals(1, receivedMessages.size(), "Should have received exactly one message");

        PipeStream receivedMessage = receivedMessages.get(0);
        assertNotNull(receivedMessage, "Received message should not be null");
        assertEquals(docId, receivedMessage.getRequest().getDoc().getId(), 
                "Document ID should match");
        assertEquals("Test Document", receivedMessage.getRequest().getDoc().getTitle(), 
                "Document title should match");
    }

    @Test
    void testForwardToBackup() throws Exception {
        // Create test data
        String docId = UUID.randomUUID().toString();
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(docId)
                .setTitle("Backup Document")
                .setBody("This is a test document for Kafka backup forwarding")
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                .build();

        PipeStream pipeStream = PipeStream.newBuilder()
                .setRequest(request)
                .build();

        Route route = Route.newBuilder()
                .setDestination(TEST_TOPIC)
                .setRouteType(RouteType.KAFKA)
                .build();

        log.info("Forwarding test message to Kafka backup topic: {}", BACKUP_TOPIC);

        // Forward the message to the backup topic
        kafkaForwarder.forwardToBackup(pipeStream, route);

        // Wait for the message to be received by the listener
        assertTrue(testListener.getBackupLatch().await(30, TimeUnit.SECONDS), 
                "Timed out waiting for backup message to be received");

        // Verify the message was received
        List<PipeStream> receivedBackupMessages = testListener.getReceivedBackupMessages();
        assertEquals(1, receivedBackupMessages.size(), "Should have received exactly one backup message");

        PipeStream receivedMessage = receivedBackupMessages.get(0);
        assertNotNull(receivedMessage, "Received backup message should not be null");
        assertEquals(docId, receivedMessage.getRequest().getDoc().getId(), 
                "Document ID should match");
        assertEquals("Backup Document", receivedMessage.getRequest().getDoc().getTitle(), 
                "Document title should match");
    }

    @Singleton
    @KafkaListener(offsetReset = OffsetReset.EARLIEST)
    public static class TestListener {
        private final List<PipeStream> receivedMessages = new CopyOnWriteArrayList<>();
        private final List<PipeStream> receivedBackupMessages = new CopyOnWriteArrayList<>();
        private CountDownLatch latch = new CountDownLatch(1);
        private CountDownLatch backupLatch = new CountDownLatch(1);

        @Topic(TEST_TOPIC)
        public void receive(PipeStream message) {
            log.info("Received message on topic {}: {}", TEST_TOPIC, message);
            receivedMessages.add(message);
            latch.countDown();
        }

        @Topic(BACKUP_TOPIC)
        public void receiveBackup(PipeStream message) {
            log.info("Received message on backup topic {}: {}", BACKUP_TOPIC, message);
            receivedBackupMessages.add(message);
            backupLatch.countDown();
        }

        public List<PipeStream> getReceivedMessages() {
            return Collections.unmodifiableList(receivedMessages);
        }

        public List<PipeStream> getReceivedBackupMessages() {
            return Collections.unmodifiableList(receivedBackupMessages);
        }

        public CountDownLatch getLatch() {
            return latch;
        }

        public CountDownLatch getBackupLatch() {
            return backupLatch;
        }

        public void reset() {
            receivedMessages.clear();
            receivedBackupMessages.clear();
            latch = new CountDownLatch(1);
            backupLatch = new CountDownLatch(1);
        }
    }
}
