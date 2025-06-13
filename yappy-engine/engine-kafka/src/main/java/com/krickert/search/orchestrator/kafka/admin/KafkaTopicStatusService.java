package com.krickert.search.orchestrator.kafka.admin;

import com.krickert.search.orchestrator.kafka.admin.model.KafkaTopicStatus;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for retrieving status information about Kafka topics.
 * This service provides methods to get detailed status information about a topic,
 * including health, offsets, consumer lag, and listener status.
 * 
 * <p>The service includes both asynchronous and synchronous methods for:</p>
 * <ul>
 *   <li>Getting the status of a single topic</li>
 *   <li>Getting the status of a topic for a specific consumer group</li>
 *   <li>Getting the status of multiple topics</li>
 *   <li>Getting the status of a topic for multiple consumer groups</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Inject the KafkaTopicStatusService
 * @Inject
 * private KafkaTopicStatusService kafkaTopicStatusService;
 * 
 * // Get the status of a topic
 * KafkaTopicStatus status = kafkaTopicStatusService.getTopicStatus("my-topic");
 * 
 * // Access the status information
 * System.out.println("Topic: " + status.topicName());
 * System.out.println("Health: " + status.healthStatus());
 * System.out.println("Partitions: " + status.partitionCount());
 * System.out.println("Largest Offset: " + status.largestOffset());
 * System.out.println("Total Lag: " + status.totalLag());
 * System.out.println("Listener Status: " + status.listenerStatus());
 * }</pre>
 * 
 * <p>The MicronautKafkaTopicStatusService implementation integrates with Micrometer to track timings and stats.
 * The following metrics are available:</p>
 * <ul>
 *   <li>{@code kafka.topic.status.time}: Time taken to retrieve topic status</li>
 *   <li>{@code kafka.topic.status.error}: Count of errors retrieving topic status</li>
 *   <li>{@code kafka.topic.partition.count}: Number of partitions in a topic</li>
 *   <li>{@code kafka.topic.replication.factor}: Replication factor of a topic</li>
 *   <li>{@code kafka.topic.largest.offset}: Largest offset in a topic</li>
 *   <li>{@code kafka.topic.consumer.lag}: Consumer lag for a topic and consumer group</li>
 * </ul>
 */
public interface KafkaTopicStatusService {

    /**
     * Gets the status of a Kafka topic.
     * 
     * @param topicName The name of the topic
     * @return CompletableFuture<KafkaTopicStatus> containing the topic status information
     */
    CompletableFuture<KafkaTopicStatus> getTopicStatusAsync(String topicName);

    /**
     * Gets the status of a Kafka topic for a specific consumer group.
     * 
     * @param topicName The name of the topic
     * @param consumerGroupId The ID of the consumer group
     * @return CompletableFuture<KafkaTopicStatus> containing the topic status information for the consumer group
     */
    CompletableFuture<KafkaTopicStatus> getTopicStatusForConsumerGroupAsync(String topicName, String consumerGroupId);

    /**
     * Gets the status of multiple Kafka topics.
     * 
     * @param topicNames The names of the topics
     * @return CompletableFuture<Map<String, KafkaTopicStatus>> containing the topic status information for each topic
     */
    CompletableFuture<Map<String, KafkaTopicStatus>> getMultipleTopicStatusAsync(Iterable<String> topicNames);

    /**
     * Gets the status of a Kafka topic for multiple consumer groups.
     * 
     * @param topicName The name of the topic
     * @param consumerGroupIds The IDs of the consumer groups
     * @return CompletableFuture<Map<String, KafkaTopicStatus>> containing the topic status information for each consumer group
     */
    CompletableFuture<Map<String, KafkaTopicStatus>> getTopicStatusForMultipleConsumerGroupsAsync(String topicName, Iterable<String> consumerGroupIds);

    /**
     * Synchronous version of getTopicStatusAsync.
     * 
     * @param topicName The name of the topic
     * @return KafkaTopicStatus containing the topic status information
     */
    KafkaTopicStatus getTopicStatus(String topicName);

    /**
     * Synchronous version of getTopicStatusForConsumerGroupAsync.
     * 
     * @param topicName The name of the topic
     * @param consumerGroupId The ID of the consumer group
     * @return KafkaTopicStatus containing the topic status information for the consumer group
     */
    KafkaTopicStatus getTopicStatusForConsumerGroup(String topicName, String consumerGroupId);

    /**
     * Synchronous version of getMultipleTopicStatusAsync.
     * 
     * @param topicNames The names of the topics
     * @return Map<String, KafkaTopicStatus> containing the topic status information for each topic
     */
    Map<String, KafkaTopicStatus> getMultipleTopicStatus(Iterable<String> topicNames);

    /**
     * Synchronous version of getTopicStatusForMultipleConsumerGroupsAsync.
     * 
     * @param topicName The name of the topic
     * @param consumerGroupIds The IDs of the consumer groups
     * @return Map<String, KafkaTopicStatus> containing the topic status information for each consumer group
     */
    Map<String, KafkaTopicStatus> getTopicStatusForMultipleConsumerGroups(String topicName, Iterable<String> consumerGroupIds);
}
