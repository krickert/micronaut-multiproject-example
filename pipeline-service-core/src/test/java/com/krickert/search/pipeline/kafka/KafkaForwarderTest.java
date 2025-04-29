package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.*;
import com.krickert.search.test.platform.AbstractPipelineTest;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class KafkaForwarderTest extends AbstractPipelineTest implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(KafkaForwarderTest.class);
    private static final String TEST_TOPIC = "test-topic";
    private static final String BACKUP_TOPIC = "backup-test-topic";

    // Store the document ID to ensure consistency between getInput() and getExpectedPipeDoc()
    private final String testDocId = UUID.randomUUID().toString();

    @Inject
    private KafkaForwarder kafkaForwarder;

    @Inject
    private TestListener testListener;

    @BeforeEach
    public void setUp() {
        // Call parent setUp method
        super.setUp();
        // Clear any messages from previous tests
        testListener.reset();
        log.info("Test setup complete");
    }

    /**
     * Get the input PipeStream for testing.
     * 
     * @return the input PipeStream
     */
    @Override
    protected PipeStream getInput() {
        // Create test data using the consistent testDocId
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(testDocId)
                .setTitle("Test Document")
                .setBody("This is a test document for Kafka forwarding")
                .build();

        PipeRequest request = PipeRequest.newBuilder()
                .setDoc(doc)
                .build();

        return PipeStream.newBuilder()
                .setRequest(request)
                .build();
    }

    /**
     * Get the expected output PipeResponse after processing.
     * 
     * @return the expected PipeResponse
     */
    @Override
    protected PipeResponse getExpectedOutput() {
        // For KafkaForwarder tests, we don't expect a specific response
        // as we're just testing the forwarding functionality
        return PipeResponse.newBuilder()
                .setSuccess(true)
                .build();
    }

    /**
     * Get the expected output PipeDoc after processing.
     * 
     * @return the expected PipeDoc
     */
    @Override
    protected PipeDoc getExpectedPipeDoc() {
        // For KafkaForwarder tests, we expect the document to remain unchanged
        return getInput().getRequest().getDoc();
    }

    /**
     * Test forwarding a message to Kafka.
     * This test uses the KafkaForwarder directly rather than the AbstractPipelineTest framework.
     */
    @Test
    void testForwardToKafka() throws Exception {
        // Get the input PipeStream
        PipeStream pipeStream = getInput();

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
        assertEquals(testDocId, receivedMessage.getRequest().getDoc().getId(), 
                "Document ID should match");
        assertEquals("Test Document", receivedMessage.getRequest().getDoc().getTitle(), 
                "Document title should match");
    }

    /**
     * Test using the AbstractPipelineTest framework's Kafka functionality.
     * This test overrides the parent method to add KafkaForwarder-specific assertions.
     */
    @Override
    @Test
    public void testKafkaInput() throws Exception {
        // Call the parent test method
        super.testKafkaInput();

        // Additional assertions specific to KafkaForwarder can be added here
        log.info("KafkaForwarder Kafka input test completed successfully");
    }

    /**
     * Test forwarding a message to the backup topic.
     * This test uses the KafkaForwarder directly rather than the AbstractPipelineTest framework.
     */
    @Test
    void testForwardToBackup() throws Exception {
        // Create a custom PipeStream for backup testing with a different title/body but same ID
        PipeDoc doc = PipeDoc.newBuilder()
                .setId(testDocId)
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
        assertEquals(testDocId, receivedMessage.getRequest().getDoc().getId(), 
                "Document ID should match");
        assertEquals("Backup Document", receivedMessage.getRequest().getDoc().getTitle(), 
                "Document title should match");
    }

    /**
     * Test using the AbstractPipelineTest framework's gRPC functionality.
     * This test overrides the parent method to add KafkaForwarder-specific assertions.
     */
    @Override
    @Test
    public void testGrpcInput() throws Exception {
        // Call the parent test method
        super.testGrpcInput();

        // Additional assertions specific to KafkaForwarder can be added here
        log.info("KafkaForwarder gRPC input test completed successfully");
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
