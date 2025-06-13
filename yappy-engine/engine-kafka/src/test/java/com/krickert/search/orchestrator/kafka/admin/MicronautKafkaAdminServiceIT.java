package com.krickert.search.orchestrator.kafka.admin;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Property(name = "kafka.enabled", value = "true")
class MicronautKafkaAdminServiceIT {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaAdminServiceIT.class);
    private static final String TEST_TOPIC_PREFIX = "admin-test-topic-";

    @Inject
    KafkaAdminService kafkaAdminService;

    private String testTopicName;

    @BeforeEach
    void setUp() {
        // Generate a unique topic name for each test
        testTopicName = TEST_TOPIC_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        LOG.info("Using test topic: {}", testTopicName);

        // Ensure the topic doesn't exist before the test
        if (kafkaAdminService.doesTopicExist(testTopicName)) {
            LOG.info("Test topic already exists, deleting it");
            kafkaAdminService.deleteTopic(testTopicName);

            // Wait a bit to ensure the topic is fully deleted
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up by deleting the test topic if it exists
        try {
            if (kafkaAdminService.doesTopicExist(testTopicName)) {
                LOG.info("Cleaning up test topic: {}", testTopicName);
                kafkaAdminService.deleteTopic(testTopicName);
            }
        } catch (Exception e) {
            LOG.warn("Error during test cleanup: {}", e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("Should create, describe, and delete a topic")
    void testCreateDescribeDeleteTopic() {
        // Create topic options
        TopicOpts topicOpts = new TopicOpts(
                3,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        // Create the topic
        assertDoesNotThrow(() -> kafkaAdminService.createTopic(topicOpts, testTopicName),
                "Should create topic without throwing an exception");

        // Verify the topic exists
        assertTrue(kafkaAdminService.doesTopicExist(testTopicName),
                "Topic should exist after creation");

        // Describe the topic
        TopicDescription topicDescription = kafkaAdminService.describeTopic(testTopicName);
        assertNotNull(topicDescription, "Topic description should not be null");
        assertEquals(testTopicName, topicDescription.name(), "Topic name should match");
        assertEquals(3, topicDescription.partitions().size(), "Topic should have 3 partitions");

        // Delete the topic
        assertDoesNotThrow(() -> kafkaAdminService.deleteTopic(testTopicName),
                "Should delete topic without throwing an exception");

        // Wait a bit to ensure the topic is fully deleted
        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Verify the topic no longer exists
        assertFalse(kafkaAdminService.doesTopicExist(testTopicName),
                "Topic should not exist after deletion");
    }

    @Test
    @DisplayName("Should update topic configuration")
    void testUpdateTopicConfiguration() {
        // Create topic options
        TopicOpts topicOpts = new TopicOpts(
                1,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        // Create the topic
        kafkaAdminService.createTopic(topicOpts, testTopicName);

        // Update topic configuration
        Map<String, String> configsToUpdate = new HashMap<>();
        configsToUpdate.put("retention.ms", "86400000"); // 1 day in milliseconds

        assertDoesNotThrow(() -> kafkaAdminService.updateTopicConfiguration(testTopicName, configsToUpdate),
                "Should update topic configuration without throwing an exception");

        // Get the updated configuration
        Config topicConfig = kafkaAdminService.getTopicConfiguration(testTopicName);
        assertNotNull(topicConfig, "Topic configuration should not be null");

        // Verify the configuration was updated
        String retentionMs = topicConfig.get("retention.ms").value();
        assertEquals("86400000", retentionMs, "retention.ms should be updated to 1 day");
    }

    @Test
    @DisplayName("Should list topics")
    void testListTopics() {
        // Create a topic
        TopicOpts topicOpts = new TopicOpts(
                1,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        kafkaAdminService.createTopic(topicOpts, testTopicName);

        // List topics
        Set<String> topics = kafkaAdminService.listTopics();
        assertNotNull(topics, "Topics list should not be null");
        assertTrue(topics.contains(testTopicName), "Topics list should contain the test topic");
    }

    @Test
    @DisplayName("Should recreate a topic")
    void testRecreateTopic() {
        // Create initial topic
        TopicOpts initialTopicOpts = new TopicOpts(
                1,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        kafkaAdminService.createTopic(initialTopicOpts, testTopicName);

        // Verify initial topic exists and has 1 partition
        TopicDescription initialDescription = kafkaAdminService.describeTopic(testTopicName);
        assertEquals(1, initialDescription.partitions().size(), "Initial topic should have 1 partition");

        // Recreate the topic with different options
        TopicOpts newTopicOpts = new TopicOpts(
                2,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        assertDoesNotThrow(() -> kafkaAdminService.recreateTopic(newTopicOpts, testTopicName),
                "Should recreate topic without throwing an exception");

        // Verify the topic was recreated with new options
        TopicDescription newDescription = kafkaAdminService.describeTopic(testTopicName);
        assertEquals(2, newDescription.partitions().size(), "Recreated topic should have 2 partitions");
    }

    @Test
    @DisplayName("Should handle asynchronous operations")
    void testAsyncOperations() throws ExecutionException, InterruptedException, TimeoutException {
        // Create topic options
        TopicOpts topicOpts = new TopicOpts(
                1,
                (short) 1,
                List.of(CleanupPolicy.DELETE));

        // Create topic asynchronously
        CompletableFuture<Void> createFuture = kafkaAdminService.createTopicAsync(topicOpts, testTopicName);
        assertDoesNotThrow(() -> createFuture.get(10, TimeUnit.SECONDS),
                "Async topic creation should complete without throwing an exception");

        // Check if topic exists asynchronously
        CompletableFuture<Boolean> existsFuture = kafkaAdminService.doesTopicExistAsync(testTopicName);
        assertTrue(existsFuture.get(10, TimeUnit.SECONDS),
                "Topic should exist after creation");

        // Describe topic asynchronously
        CompletableFuture<TopicDescription> describeFuture = kafkaAdminService.describeTopicAsync(testTopicName);
        TopicDescription description = describeFuture.get(10, TimeUnit.SECONDS);
        assertEquals(testTopicName, description.name(), "Topic name should match");

        // Delete topic asynchronously
        CompletableFuture<Void> deleteFuture = kafkaAdminService.deleteTopicAsync(testTopicName);
        assertDoesNotThrow(() -> deleteFuture.get(10, TimeUnit.SECONDS),
                "Async topic deletion should complete without throwing an exception");

        // Wait a bit to ensure the topic is fully deleted
        TimeUnit.SECONDS.sleep(2);

        // Verify topic no longer exists asynchronously
        CompletableFuture<Boolean> notExistsFuture = kafkaAdminService.doesTopicExistAsync(testTopicName);
        assertFalse(notExistsFuture.get(10, TimeUnit.SECONDS),
                "Topic should not exist after deletion");
    }

    // TODO: Add tests for consumer group operations once they are fully implemented
    // For now, we'll add a placeholder test that verifies the service throws UnsupportedOperationException
    // for the consumer group methods that are not yet implemented

    @Test
    @DisplayName("Should throw UnsupportedOperationException for unimplemented consumer group methods")
    void testUnimplementedConsumerGroupMethods() {
        // describeConsumerGroupAsync
        CompletableFuture<Void> future1 = kafkaAdminService.describeConsumerGroupAsync("test-group")
                .handle((result, ex) -> {
                    assertInstanceOf(UnsupportedOperationException.class, ex.getCause(), "describeConsumerGroupAsync should throw UnsupportedOperationException");
                    return null;
                });

        // listConsumerGroupsAsync
        CompletableFuture<Void> future2 = kafkaAdminService.listConsumerGroupsAsync()
                .handle((result, ex) -> {
                    assertInstanceOf(UnsupportedOperationException.class, ex.getCause(), "listConsumerGroupsAsync should throw UnsupportedOperationException");
                    return null;
                });

        // deleteConsumerGroupAsync
        CompletableFuture<Void> future3 = kafkaAdminService.deleteConsumerGroupAsync("test-group")
                .handle((result, ex) -> {
                    assertInstanceOf(UnsupportedOperationException.class, ex.getCause(), "deleteConsumerGroupAsync should throw UnsupportedOperationException");
                    return null;
                });

        // Wait for all futures to complete
        CompletableFuture.allOf(future1, future2, future3).join();
    }
}
