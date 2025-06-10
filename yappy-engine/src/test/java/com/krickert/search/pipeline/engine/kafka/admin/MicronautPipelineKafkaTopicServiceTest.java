package com.krickert.search.pipeline.engine.kafka.admin;

import com.krickert.search.pipeline.engine.kafka.admin.config.PipelineKafkaTopicConfig;
import com.krickert.search.pipeline.engine.kafka.admin.exceptions.KafkaAdminServiceException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class MicronautPipelineKafkaTopicServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautPipelineKafkaTopicServiceTest.class);

    @Inject
    private PipelineKafkaTopicService pipelineKafkaTopicService;

    @Inject
    private KafkaAdminService kafkaAdminService;

    @Inject
    private PipelineKafkaTopicConfig topicConfig;

    private String testPipelineName;
    private String testStepName;
    private List<String> createdTopics;

    @BeforeEach
    void setUp() {
        // Generate unique pipeline and step names for each test
        testPipelineName = "test-pipeline-" + UUID.randomUUID().toString().substring(0, 8);
        testStepName = "test-step-" + UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Test pipeline: {}, step: {}", testPipelineName, testStepName);
    }

    @AfterEach
    void tearDown() {
        // Clean up any topics created during the test
        if (createdTopics != null) {
            for (String topic : createdTopics) {
                try {
                    if (kafkaAdminService.doesTopicExist(topic)) {
                        LOG.info("Cleaning up test topic: {}", topic);
                        kafkaAdminService.deleteTopic(topic);
                    }
                } catch (Exception e) {
                    LOG.warn("Error during test cleanup: {}", e.getMessage(), e);
                }
            }
        }
    }

    /**
     * Waits for the specified topics to be created in Kafka using Reactor.
     * @param topics Collection of topic names to wait for
     * @param timeout Maximum time to wait
     */
    private void waitForTopics(Collection<String> topics, Duration timeout) {
        Flux.fromIterable(topics)
            .flatMap(topic -> 
                // Poll until topic exists
                Flux.interval(Duration.ZERO, Duration.ofMillis(100))
                    .flatMap(i -> Mono.fromCallable(() -> kafkaAdminService.doesTopicExist(topic)))
                    .filter(exists -> exists)
                    .next() // Take the first 'true' result
                    .timeout(timeout)
                    .switchIfEmpty(Mono.error(new TimeoutException("Topic " + topic + " not created within timeout")))
            )
            .blockLast(timeout); // Block until all topics exist or timeout
    }

    @Test
    @DisplayName("Should generate correct topic names")
    void testGenerateTopicName() {
        // Test topic name generation for each topic type
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
            String expectedName = String.format("pipeline.%s.step.%s.%s", testPipelineName, testStepName, topicType.getSuffix());
            assertEquals(expectedName, topicName, "Topic name should match expected pattern");
        }
    }

    @Test
    @DisplayName("Should create a topic with correct configuration")
    void testCreateTopic() {
        // Create a topic
        PipelineKafkaTopicService.TopicType topicType = PipelineKafkaTopicService.TopicType.INPUT;
        pipelineKafkaTopicService.createTopic(testPipelineName, testStepName, topicType);

        // Get the topic name
        String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
        createdTopics = List.of(topicName);

        // Verify the topic exists
        assertTrue(kafkaAdminService.doesTopicExist(topicName), "Topic should exist after creation");

        // Describe the topic
        TopicDescription topicDescription = kafkaAdminService.describeTopic(topicName);
        assertNotNull(topicDescription, "Topic description should not be null");
        assertEquals(topicName, topicDescription.name(), "Topic name should match");
        assertEquals(topicConfig.getPartitions(), topicDescription.partitions().size(), 
                "Topic should have the configured number of partitions");

        // Get the topic configuration
        Config topicConfig = kafkaAdminService.getTopicConfiguration(topicName);
        assertNotNull(topicConfig, "Topic configuration should not be null");

        // Verify the configuration matches the expected values
        String cleanupPolicy = topicConfig.get(TopicConfig.CLEANUP_POLICY_CONFIG).value();
        assertTrue(cleanupPolicy.contains("compact"), "Cleanup policy should include compact");
        assertTrue(cleanupPolicy.contains("delete"), "Cleanup policy should include delete");

        String compressionType = topicConfig.get(TopicConfig.COMPRESSION_TYPE_CONFIG).value();
        assertEquals(this.topicConfig.getCompressionType(), compressionType, "Compression type should match configured value");

        String maxMessageBytes = topicConfig.get(TopicConfig.MAX_MESSAGE_BYTES_CONFIG).value();
        assertEquals(String.valueOf(this.topicConfig.getMaxMessageBytes()), maxMessageBytes, 
                "Max message bytes should match configured value");

        String retentionMs = topicConfig.get(TopicConfig.RETENTION_MS_CONFIG).value();
        assertEquals(String.valueOf(this.topicConfig.getRetentionMs()), retentionMs, 
                "Retention ms should match configured value");
    }

    @Test
    @DisplayName("Should create all topics for a pipeline step")
    void testCreateAllTopics() {
        // Create all topics for the pipeline step
        pipelineKafkaTopicService.createAllTopics(testPipelineName, testStepName);

        // Generate expected topic names
        List<String> expectedTopics = new ArrayList<>();
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            expectedTopics.add(pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType));
        }
        
        // Wait for all topics to be created
        waitForTopics(expectedTopics, Duration.ofSeconds(10));

        // Get the list of topics for the step
        List<String> topics = pipelineKafkaTopicService.listTopicsForStep(testPipelineName, testStepName);
        createdTopics = topics;

        // Verify all topic types were created
        assertEquals(PipelineKafkaTopicService.TopicType.values().length, topics.size(), 
                "Should create a topic for each topic type");

        // Verify each topic type exists
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
            assertTrue(topics.contains(topicName), "Topics list should contain " + topicType + " topic");
            assertTrue(kafkaAdminService.doesTopicExist(topicName), topicType + " topic should exist");
        }
    }

    @Test
    @DisplayName("Should list topics for a pipeline step")
    void testListTopicsForStep() {
        // Create all topics for the pipeline step
        pipelineKafkaTopicService.createAllTopics(testPipelineName, testStepName);

        // Generate expected topic names
        List<String> expectedTopics = new ArrayList<>();
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            expectedTopics.add(pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType));
        }
        
        // Wait for all topics to be created
        waitForTopics(expectedTopics, Duration.ofSeconds(10));
        
        // Now list the topics
        List<String> topics = pipelineKafkaTopicService.listTopicsForStep(testPipelineName, testStepName);
        createdTopics = topics;

        // Verify all topic types were created and are in the list
        assertEquals(PipelineKafkaTopicService.TopicType.values().length, topics.size(), 
                "Should list all topics for the step");

        // Verify each topic type is in the list
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
            assertTrue(topics.contains(topicName), "Topics list should contain " + topicType + " topic");
        }
    }

    @Test
    @DisplayName("Should create topics asynchronously")
    void testCreateTopicAsync() throws ExecutionException, InterruptedException, TimeoutException {
        // Create a topic asynchronously
        PipelineKafkaTopicService.TopicType topicType = PipelineKafkaTopicService.TopicType.OUTPUT;
        pipelineKafkaTopicService.createTopicAsync(testPipelineName, testStepName, topicType)
                .get(30, TimeUnit.SECONDS); // Wait for completion with timeout

        // Get the topic name
        String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
        createdTopics = List.of(topicName);

        // Verify the topic exists
        assertTrue(kafkaAdminService.doesTopicExist(topicName), "Topic should exist after async creation");
    }

    @Test
    @DisplayName("Should create all topics asynchronously")
    void testCreateAllTopicsAsync() throws ExecutionException, InterruptedException, TimeoutException {
        // Create all topics asynchronously
        pipelineKafkaTopicService.createAllTopicsAsync(testPipelineName, testStepName)
                .get(30, TimeUnit.SECONDS); // Wait for completion with timeout

        // Generate expected topic names
        List<String> expectedTopics = new ArrayList<>();
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            expectedTopics.add(pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType));
        }
        
        // Wait for all topics to be created
        waitForTopics(expectedTopics, Duration.ofSeconds(10));

        // Get the list of topics for the step
        List<String> topics = pipelineKafkaTopicService.listTopicsForStep(testPipelineName, testStepName);
        createdTopics = topics;

        // Verify all topic types were created
        assertEquals(PipelineKafkaTopicService.TopicType.values().length, topics.size(), 
                "Should create all topics asynchronously");

        // Verify each topic type exists
        for (PipelineKafkaTopicService.TopicType topicType : PipelineKafkaTopicService.TopicType.values()) {
            String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, topicType);
            assertTrue(topics.contains(topicName), "Topics list should contain " + topicType + " topic");
            assertTrue(kafkaAdminService.doesTopicExist(topicName), topicType + " topic should exist after async creation");
        }
    }

    @Test
    @DisplayName("Should throw exception when replication factor exceeds available brokers")
    void testReplicationFactorExceedsAvailableBrokers() {
        // Get the number of available brokers
        int availableBrokers = kafkaAdminService.getAvailableBrokerCount();
        LOG.info("Available brokers: {}", availableBrokers);

        // Create a topic with replication factor greater than available brokers
        short excessiveReplicationFactor = (short) (availableBrokers + 1);
        String topicName = pipelineKafkaTopicService.generateTopicName(testPipelineName, testStepName, PipelineKafkaTopicService.TopicType.INPUT);

        // Access the MicronautPipelineKafkaTopicService implementation to use the method that bypasses automatic adjustment
        MicronautPipelineKafkaTopicService micronautService = (MicronautPipelineKafkaTopicService) pipelineKafkaTopicService;
        TopicOpts topicOpts = micronautService.createTopicOptsWithoutAdjustment(excessiveReplicationFactor);

        // Verify that creating a topic with excessive replication factor throws an exception
        Exception exception = assertThrows(KafkaAdminServiceException.class, () -> {
            kafkaAdminService.createTopic(topicOpts, topicName);
        });

        // Verify the exception message
        assertTrue(exception.getMessage().equals("Kafka admin operation failed: Replication factor: 2 larger than available brokers: 1.") ||
                exception.getMessage().contains(String.format("Kafka admin operation failed: Unable to replicate the partition %s time(s): The target replication factor of %s cannot be reached because only %s broker(s) are registered.",excessiveReplicationFactor, excessiveReplicationFactor, availableBrokers)),
                "Exception should mention replication factor exceeding available brokers");

        // Add the topic to createdTopics for cleanup, even though creation should fail
        createdTopics = List.of(topicName);
    }
}
