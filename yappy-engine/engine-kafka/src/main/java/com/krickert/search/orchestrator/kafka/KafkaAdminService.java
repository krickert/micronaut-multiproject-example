package com.krickert.search.orchestrator.kafka;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service for Kafka administrative operations like topic management.
 */
public interface KafkaAdminService {

    /**
     * Create a new Kafka topic if it doesn't exist.
     * 
     * @param topicName the name of the topic to create
     * @param partitions number of partitions for the topic
     * @param replicationFactor replication factor for the topic
     * @return CompletableFuture that completes when topic is created or already exists
     */
    CompletableFuture<Boolean> createTopicIfNotExists(String topicName, int partitions, short replicationFactor);

    /**
     * Check if a topic exists.
     * 
     * @param topicName the name of the topic to check
     * @return CompletableFuture that completes with true if topic exists, false otherwise
     */
    CompletableFuture<Boolean> topicExists(String topicName);

    /**
     * Get all existing topic names.
     * 
     * @return CompletableFuture that completes with set of topic names
     */
    CompletableFuture<Set<String>> listTopics();

    /**
     * Delete a topic.
     * 
     * @param topicName the name of the topic to delete
     * @return CompletableFuture that completes when topic is deleted
     */
    CompletableFuture<Boolean> deleteTopic(String topicName);
}