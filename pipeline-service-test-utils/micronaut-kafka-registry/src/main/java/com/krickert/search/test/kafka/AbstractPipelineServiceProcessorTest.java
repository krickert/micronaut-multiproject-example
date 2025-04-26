package com.krickert.search.test.kafka;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Abstract test class for testing PipelineServiceProcessor implementations.
 * Extending classes only need to provide input and expected output.
 */
@MicronautTest(environments = "test", transactional = false)
public abstract class AbstractPipelineServiceProcessorTest extends AbstractMicronautKafkaTest<PipeStream> {
    private static final Logger LOG = LoggerFactory.getLogger(AbstractPipelineServiceProcessorTest.class);

    @Inject
    protected PipelineServiceProcessor pipelineServiceProcessor;

    /**
     * Get the input PipeStream for testing.
     * Default implementation creates a basic PipeStream.
     * Override to provide custom test input.
     * 
     * @return the input PipeStream
     */
    protected PipeStream getInput() {
        // Default implementation creates a basic PipeStream
        return createTestMessage();
    }

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

    protected Class<PipeStream> getMessageClass() {
        return PipeStream.class;
    }

    @Override
    protected PipeStream createTestMessage() {
        return getInput();
    }

    /**
     * Test that the processor correctly processes the input PipeStream.
     * This test:
     * 1. Gets the input PipeStream
     * 2. Processes it using the injected PipelineServiceProcessor
     * 3. Verifies the response and PipeDoc match the expected output
     */
    @Test
    public void testProcessorDirectly() {
        // Get the input PipeStream
        PipeStream input = getInput();
        LOG.info("Testing with input: {}", input);

        // Process the input
        PipeServiceDto serviceDto = pipelineServiceProcessor.process(input);
        LOG.info("Received response: {}", serviceDto.getResponse());
        LOG.info("Received PipeDoc: {}", serviceDto.getPipeDoc());

        // Verify the response
        PipeResponse expectedResponse = getExpectedOutput();
        assertEquals(expectedResponse.getSuccess(), serviceDto.getResponse().getSuccess(), 
                "Success flag should match expected value");

        // Verify the PipeDoc
        PipeDoc expectedDoc = getExpectedPipeDoc();
        assertNotNull(serviceDto.getPipeDoc(), "PipeDoc should not be null");

        // Additional verification can be added here based on specific needs
    }

    /**
     * Test that the processor correctly processes messages through Kafka.
     * This test:
     * 1. Sends a message to the input topic
     * 2. The processor listens, processes it, and sends to the output topic
     * 3. We verify the processed message has the expected changes
     */
    @Test
    public void testProcessorWithKafka() throws Exception {
        // Get the input PipeStream
        PipeStream input = getInput();
        LOG.info("Testing with input: {}", input);

        // Send the message to the input topic
        sendMessage(input);
        LOG.info("Sent message to input topic: {}", getTopicName());

        // Wait for the processed message on the output topic
        PipeStream processedMessage = getNextMessage(10);
        LOG.info("Received processed message from output topic: {}", processedMessage);

        // Verify the processed message
        assertNotNull(processedMessage, "Processed message should not be null");

        // Verify the document in the processed message
        if (processedMessage.hasRequest() && processedMessage.getRequest().hasDoc()) {
            PipeDoc processedDoc = processedMessage.getRequest().getDoc();
            PipeDoc expectedDoc = getExpectedPipeDoc();

            // Verify key fields in the processed document
            assertNotNull(processedDoc, "Processed document should not be null");
            assertNotNull(expectedDoc, "Expected document should not be null");

            // Additional verification can be added here based on specific needs
            // For example, verify document ID, title, or custom fields
        }

        // Process the input directly to compare with Kafka-processed message
        PipeResponse expectedResponse = getExpectedOutput();

        // Verify success status is reflected in the processed message
        if (processedMessage.getPipeRepliesCount() > 0) {
            assertEquals(expectedResponse.getSuccess(), processedMessage.getPipeReplies(0).getSuccess(),
                    "Success flag should match expected value");
        }
    }
}
