package com.krickert.search.pipeline.kafka;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.PipeResponse;
import com.krickert.search.model.PipeStream;
import static com.krickert.search.model.ProtobufUtils.createKey;
import com.krickert.search.pipeline.config.InternalServiceConfig;
import com.krickert.search.pipeline.config.PipelineConfigService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.event.ApplicationEventPublisher;
import io.micronaut.discovery.event.ServiceReadyEvent;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for DynamicKafkaConsumerManager without using mocks.
 * This test verifies that the manager correctly creates consumers and processes messages.
 */
@MicronautTest(environments = "apicurio", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DynamicKafkaConsumerIntegrationTest extends DynamicKafkaConsumerTestBase implements TestPropertyProvider {
    private static final Logger log = LoggerFactory.getLogger(DynamicKafkaConsumerIntegrationTest.class);

    // Store the input PipeStream to ensure consistency between getInput() and getExpectedPipeDoc()
    private PipeStream cachedInput;

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = super.getProperties();
        // Enable DynamicKafkaConsumerManager
        properties.put("kafka.consumer.dynamic.enabled", "true");

        // Configure the executor service
        properties.put("micronaut.executors.dynamic-kafka-consumer-executor.type", "fixed");
        properties.put("micronaut.executors.dynamic-kafka-consumer-executor.nThreads", "5");

        // Set the pipeline service name
        properties.put("pipeline.service.name", "test-service");

        return properties;
    }

    /**
     * Factory for test beans.
     */
    @Factory
    static class TestBeanFactory {
        /**
         * Provides an ExecutorService for the DynamicKafkaConsumerManager.
         * 
         * @return An ExecutorService.
         */
        @Bean
        @Singleton
        @Primary
        @Named("dynamic-kafka-consumer-executor")
        ExecutorService dynamicKafkaExecutor() {
            return Executors.newFixedThreadPool(5);
        }
    }

    @Inject
    private DynamicKafkaConsumerManager consumerManager;

    @Inject
    private PipelineConfigService configService;

    @Inject
    private InternalServiceConfig internalServiceConfig;

    @Inject
    private ApplicationEventPublisher eventPublisher;

    /**
     * Set up the test environment before each test.
     * This method ensures that consumers are created before each test.
     */
    @BeforeEach
    public void setUpConsumers() throws Exception {
        log.info("Setting up consumers for test");
        // Trigger synchronization of consumers
        consumerManager.synchronizeConsumers();
        // Wait a bit for consumers to start
        TimeUnit.SECONDS.sleep(2);
    }

    /**
     * Get the input PipeStream for testing.
     * 
     * @return the input PipeStream
     */
    @Override
    protected PipeStream getInput() {
        if (cachedInput == null) {
            // Create a simple PipeStream for testing
            PipeDoc doc = PipeDoc.newBuilder()
                    .setId("test-doc-" + UUID.randomUUID())
                    .setTitle("Test Document")
                    .setBody("This is a test document for dynamic consumer integration test")
                    .build();

            cachedInput = PipeStream.newBuilder()
                    .setRequest(com.krickert.search.model.PipeRequest.newBuilder().setDoc(doc).build())
                    .build();
        }
        return cachedInput;
    }

    /**
     * Get the expected output PipeResponse after processing.
     * 
     * @return the expected PipeResponse
     */
    @Override
    protected PipeResponse getExpectedOutput() {
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
        // Ensure we're using the same document that was used in the test
        return getInput().getRequest().getDoc();
    }

    /**
     * Test that the DynamicKafkaConsumerManager creates consumers and processes messages.
     * This is a pure integration test that verifies the end-to-end functionality.
     */
    @Test
    public void testDynamicConsumerProcessesMessages() throws Exception {
        log.info("Starting testDynamicConsumerProcessesMessages");

        // Verify that the DynamicKafkaConsumerManager is properly injected
        assertNotNull(consumerManager, "DynamicKafkaConsumerManager should be injected");

        // Trigger synchronization of consumers
        log.info("Triggering synchronization of consumers");
        consumerManager.synchronizeConsumers();

        // Wait a bit for consumers to start
        TimeUnit.SECONDS.sleep(2);

        // Get the input PipeStream
        PipeStream input = getInput();
        log.info("Testing with input: {}", input);

        // Reset the consumer to ensure we only get messages from this test
        consumer.reset();

        // Send the message to the input topic
        log.info("Sending message to input topic: {}", TOPIC_IN);
        producer.send(TOPIC_IN, createKey(input), input);

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

        // Verify key fields in the processed document
        assertEquals(originalDoc.getId(), processedDoc.getId(), "Document ID should not change");
        assertEquals(originalDoc.getTitle(), processedDoc.getTitle(), "Document title should not change");
        assertEquals(originalDoc.getBody(), processedDoc.getBody(), "Document body should not change");

        log.info("testDynamicConsumerProcessesMessages completed successfully");
    }
}
