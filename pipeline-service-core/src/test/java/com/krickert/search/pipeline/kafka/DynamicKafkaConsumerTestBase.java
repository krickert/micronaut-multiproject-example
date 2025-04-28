package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeStream;
import com.krickert.search.test.platform.AbstractPipelineTest;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.krickert.search.model.ProtobufUtils.createKey;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for tests that use the DynamicKafkaConsumerManager.
 * This class overrides the testKafkaInput method from AbstractPipelineTest
 * to make it compatible with the DynamicKafkaConsumerManager.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class DynamicKafkaConsumerTestBase extends AbstractPipelineTest {
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaConsumerTestBase.class);

    /**
     * Override the testKafkaInput method to make it compatible with the DynamicKafkaConsumerManager.
     * This method is marked with @Requires(property = "kafka.consumer.dynamic.enabled", value = "true")
     * to ensure it's only run when the DynamicKafkaConsumerManager is enabled.
     */
    @Test
    @Requires(property = "kafka.consumer.dynamic.enabled", value = "true")
    @Override
    public void testKafkaInput() throws Exception {
        log.info("Starting testKafkaInput with DynamicKafkaConsumerManager");

        // Get the input PipeStream
        PipeStream input = getInput();
        log.info("Testing Kafka input with: {}", input);

        // Reset the consumer to ensure we only get messages from this test
        consumer.reset();

        // Send the message to the input topic
        log.info("Sending message to input topic: {}", TOPIC_IN);
        producer.send(TOPIC_IN, createKey(input), input);

        // Wait a bit for the message to be processed
        // This is necessary because the DynamicKafkaConsumerManager processes messages asynchronously
        TimeUnit.SECONDS.sleep(5);

        // Wait for the processed message on the output topic
        log.info("Waiting for processed message on output topic: {}", TOPIC_OUT);
        PipeStream processedMessage = consumer.getNextMessage(10);
        log.info("Received processed message: {}", processedMessage);

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

        log.info("testKafkaInput completed successfully");
    }
}