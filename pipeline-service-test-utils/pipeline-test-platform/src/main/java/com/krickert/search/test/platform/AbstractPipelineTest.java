package com.krickert.search.test.platform;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.*;
import com.krickert.search.pipeline.kafka.KafkaForwarderClient;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import com.krickert.search.test.platform.kafka.KafkaApicurioTest;
import io.micronaut.configuration.kafka.annotation.KafkaClient;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.krickert.search.model.ProtobufUtils.createKey;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract base class for pipeline tests.
 * This class provides common functionality for testing pipeline service processors
 * with both gRPC and Kafka input methods.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractPipelineTest extends KafkaApicurioTest implements TestPropertyProvider {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractPipelineTest.class);
    protected static final String TOPIC_IN = "test-pipeline-input";
    protected static final String TOPIC_OUT = "test-pipeline-output";

    // Static initializer to ensure containers are started before Micronaut context initialization
    static {
        KafkaApicurioTest test = new KafkaApicurioTest(PipeStream.class.getName());
        test.startContainers();
        LOG.info("Containers started via static initializer");
    }

    /**
     * Constructor that explicitly sets the return class to PipeStream.
     */
    public AbstractPipelineTest() {
        super(PipeStream.class.getName());
    }

    @Inject
    protected PipelineServiceProcessor pipelineServiceProcessor;

    @Inject
    protected KafkaForwarderClient producer;

    @Inject
    protected PipeStreamConsumer consumer;

    @BeforeEach
    public void setup() {
        // Reset the consumer for each test
        consumer.reset();
    }

    /**
     * Get the input PipeStream for testing.
     * Must be implemented by subclasses.
     * 
     * @return the input PipeStream
     */
    protected abstract PipeStream getInput();

    /**
     * Get the expected output PipeResponse after processing.
     * Must be implemented by subclasses.
     * 
     * @return the expected PipeResponse
     */
    protected abstract PipeResponse getExpectedOutput();

    /**
     * Get the expected output PipeDoc after processing.
     * Must be implemented by subclasses.
     * 
     * @return the expected PipeDoc
     */
    protected abstract PipeDoc getExpectedPipeDoc();

    /**
     * Test that verifies the gRPC functionality by directly calling the processor.
     */
    @Test
    public void testGrpcInput() {
        // Get the input PipeStream
        PipeStream input = getInput();

        // Process the input directly (simulating a gRPC call)
        PipeServiceDto result = pipelineServiceProcessor.process(input);
        PipeDoc processedDoc = result.getPipeDoc();

        // Verify the document has the expected changes
        assertNotNull(processedDoc, "Processed document should not be null");
        
        // Compare with expected PipeDoc
        PipeDoc expectedDoc = getExpectedPipeDoc();
        assertNotNull(expectedDoc, "Expected document should not be null");
        
        // Compare the input and output
        PipeDoc originalDoc = input.getRequest().getDoc();
        assertEquals(expectedDoc.getId(), processedDoc.getId(), "Document ID should not change");
        assertEquals(expectedDoc.getTitle(), processedDoc.getTitle(), "Document title should not change");
        assertEquals(expectedDoc.getBody(), processedDoc.getBody(), "Document body should not change");

        // Verify the response
        PipeResponse response = result.getResponse();
        PipeResponse expectedResponse = getExpectedOutput();
        assertEquals(expectedResponse.getSuccess(), response.getSuccess(), "Response success should match expected");
    }

    /**
     * Test that verifies the Kafka functionality by sending a message to Kafka.
     */
    @Test
    public void testKafkaInput() throws Exception {
        // Get the input PipeStream
        PipeStream input = getInput();
        LOG.info("Testing Kafka input with: {}", input);

        // Send the message to the input topic
        producer.send(TOPIC_IN, createKey(input), input);
        LOG.info("Sent message to input topic: {}", TOPIC_IN);

        // Wait for the processed message on the output topic
        PipeStream processedMessage = consumer.getNextMessage(10);
        LOG.info("Received processed message from output topic: {}", processedMessage);

        // Verify the processed message
        assertNotNull(processedMessage, "Processed message should not be null");
        assertTrue(processedMessage.hasRequest(), "Processed message should have a request");
        assertTrue(processedMessage.getRequest().hasDoc(), "Processed message should have a document");

        // Verify the document in the processed message
        PipeDoc processedDoc = processedMessage.getRequest().getDoc();
        PipeDoc originalDoc = input.getRequest().getDoc();
        PipeDoc expectedDoc = getExpectedPipeDoc();

        // Verify key fields in the processed document
        assertEquals(originalDoc.getId(), processedDoc.getId(), "Document ID should not change");
        assertEquals(originalDoc.getTitle(), processedDoc.getTitle(), "Document title should not change");
        assertEquals(originalDoc.getBody(), processedDoc.getBody(), "Document body should not change");

        // Additional verification can be added by subclasses
    }

    /**
     * Kafka processor that listens to the input topic, processes messages,
     * and sends them to the output topic.
     */
    @Singleton
    @KafkaListener(groupId = "test-processor-group")
    public static class PipeStreamProcessor {
        private static final Logger LOG = LoggerFactory.getLogger(PipeStreamProcessor.class);

        @Inject
        private PipelineServiceProcessor processor;

        @Inject
        private PipeStreamSender sender;

        @Topic(TOPIC_IN)
        public void process(PipeStream message) {
            LOG.info("Processing message: {}", message);

            // Process the message using the PipelineServiceProcessor
            PipeServiceDto result = processor.process(message);
            PipeDoc processedDoc = result.getPipeDoc();

            // Create a new PipeStream with the processed document
            PipeStream processedMessage = message.toBuilder()
                .setRequest(message.getRequest().toBuilder()
                    .setDoc(processedDoc)
                    .build())
                .build();

            LOG.info("Processed message: {}", processedMessage);

            // Send the processed message to the output topic
            try {
                sender.send(processedMessage).get(10, TimeUnit.SECONDS);
                LOG.info("Sent processed message to output topic");
            } catch (Exception e) {
                LOG.error("Error sending processed message to output topic", e);
            }
        }
    }

    /**
     * Kafka client for sending processed messages to the output topic.
     */
    @KafkaClient
    public interface PipeStreamSender {
        @Topic(TOPIC_OUT)
        CompletableFuture<Void> send(PipeStream message);
    }

    /**
     * Kafka listener for receiving processed PipeStream messages.
     */
    @Singleton
    @KafkaListener(groupId = "test-consumer-group")
    public static class PipeStreamConsumer {
        private static final Logger LOG = LoggerFactory.getLogger(PipeStreamConsumer.class);
        private final List<PipeStream> receivedMessages = new ArrayList<>();
        private CompletableFuture<PipeStream> nextMessage = new CompletableFuture<>();

        @Topic(TOPIC_OUT)
        public void receive(PipeStream message) {
            LOG.info("Received processed message: {}", message);
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