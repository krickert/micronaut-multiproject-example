package com.krickert.search.pipeline.kafka.admin;

import com.krickert.search.pipeline.kafka.admin.exceptions.*;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for administering Kafka topics and consumer groups.
 * Methods suffixed with "Async" return CompletableFuture for non-blocking operations.
 * Implementations should provide corresponding synchronous blocking wrappers.
 */
public interface KafkaAdminService {

    // --- Topic Management ---

    /**
     * Creates a new Kafka topic.
     * @param topicOpts The options for the new topic.
     * @param topicName The name of the topic to create.
     * @return CompletableFuture<Void> that completes when the topic creation request is sent.
     * @throws TopicAlreadyExistsException if the topic already exists (implementation dependent, typically from Kafka).
     * @throws KafkaAdminServiceException for other Kafka related errors.
     */
    CompletableFuture<Void> createTopicAsync(TopicOpts topicOpts, String topicName);

    /**
     * Deletes a Kafka topic.
     * @param topicName The name of the topic to delete.
     * @return CompletableFuture<Void> that completes when the topic deletion request is sent.
     * @throws TopicNotFoundException if the topic does not exist (implementation dependent).
     * @throws KafkaAdminServiceException for other Kafka related errors.
     */
    CompletableFuture<Void> deleteTopicAsync(String topicName);

    /**
     * Recreates a Kafka topic by deleting it if it exists, and then creating it.
     * This operation includes internal polling to confirm deletion before attempting creation.
     * @param topicOpts The options for the new topic.
     * @param topicName The name of the topic to recreate.
     * @return CompletableFuture<Void> that completes when the topic recreation process is initiated successfully.
     * The future completes exceptionally if deletion or creation fails, or if polling for deletion times out.
     * @throws KafkaAdminServiceException for other Kafka related errors.
     */
    CompletableFuture<Void> recreateTopicAsync(TopicOpts topicOpts, String topicName);

    /**
     * Checks if a topic exists.
     * @param topicName The name of the topic.
     * @return CompletableFuture<Boolean> indicating whether the topic exists.
     */
    CompletableFuture<Boolean> doesTopicExistAsync(String topicName);

    /**
     * Retrieves the detailed description of a topic.
     * @param topicName The name of the topic.
     * @return CompletableFuture<TopicDescription> containing topic details.
     * @throws TopicNotFoundException if the topic does not exist.
     */
    CompletableFuture<TopicDescription> describeTopicAsync(String topicName);

    /**
     * Lists all topic names in the Kafka cluster.
     * @return CompletableFuture<Set<String>> containing all topic names.
     */
    CompletableFuture<Set<String>> listTopicsAsync();

    /**
     * Retrieves the configuration of a specific topic.
     * @param topicName The name of the topic.
     * @return CompletableFuture<Config> containing the topic configuration.
     * @throws TopicNotFoundException if the topic does not exist.
     */
    CompletableFuture<Config> getTopicConfigurationAsync(String topicName);

    /**
     * Updates the configuration of a specific topic.
     * @param topicName The name of the topic.
     * @param configsToUpdate A map of configuration keys and their new values.
     * (Note: AdminClient uses AlterConfigOp for more granular changes like APPEND/SUBTRACT/DELETE)
     * For simplicity, this could be a map, or you could expose AlterConfigOp directly.
     * Let's assume simple Map<String, String> for now, which means SET operation.
     * @return CompletableFuture<Void>
     * @throws TopicNotFoundException if the topic does not exist.
     * @throws InvalidTopicConfigurationException if the configuration update is invalid.
     */
    CompletableFuture<Void> updateTopicConfigurationAsync(String topicName, Map<String, String> configsToUpdate);


    // --- Consumer Group Management ---

    /**
     * Resets the consumer group offsets for a given topic according to the specified parameters.
     * @param groupId The ID of the consumer group.
     * @param topicName The name of the topic.
     * @param params Parameters defining how to reset the offsets (strategy, specific offsets, timestamp).
     * @return CompletableFuture<Void>
     * @throws ConsumerGroupNotFoundException if the consumer group does not exist or is not active on the topic.
     * @throws TopicNotFoundException if the topic does not exist.
     */
    CompletableFuture<Void> resetConsumerGroupOffsetsAsync(String groupId, String topicName, OffsetResetParameters params);

    /**
     * Retrieves the description of a consumer group.
     * @param groupId The ID of the consumer group.
     * @return CompletableFuture<ConsumerGroupDescription>
     * @throws ConsumerGroupNotFoundException if the consumer group does not exist.
     */
    CompletableFuture<ConsumerGroupDescription> describeConsumerGroupAsync(String groupId);

    /**
     * Lists all consumer group IDs.
     * @return CompletableFuture<Set<String>> containing all consumer group IDs.
     */
    CompletableFuture<Set<String>> listConsumerGroupsAsync();

    /**
     * Deletes a consumer group and its committed offsets. Use with caution.
     * @param groupId The ID of the consumer group to delete.
     * @return CompletableFuture<Void>
     * @throws ConsumerGroupNotFoundException if the consumer group does not exist.
     */
    CompletableFuture<Void> deleteConsumerGroupAsync(String groupId);


    // --- Status & Lag Monitoring ---

    /**
     * Retrieves the lag for each partition for a given consumer group and topic.
     * Lag is the difference between the end offset of a partition and the committed offset by the group.
     * @param groupId The ID of the consumer group.
     * @param topicName The name of the topic.
     * @return CompletableFuture<Map<TopicPartition, Long>> a map of topic-partition to its lag.
     * Returns an empty map if the group has no offsets for the topic or group/topic not found.
     */
    CompletableFuture<Map<TopicPartition, Long>> getConsumerLagPerPartitionAsync(String groupId, String topicName);

    /**
     * Retrieves the total number of messages the consumer group is behind for a specific topic.
     * This is the sum of lags across all partitions of the topic for that group.
     * @param groupId The ID of the consumer group.
     * @param topicName The name of the topic.
     * @return CompletableFuture<Long> the total lag.
     */
    CompletableFuture<Long> getTotalConsumerLagAsync(String groupId, String topicName);

    void createTopic(TopicOpts topicOpts, String topicName);

    void deleteTopic(String topicName);

    boolean doesTopicExist(String topicName);

    void recreateTopic(TopicOpts topicOpts, String topicName);

    TopicDescription describeTopic(String topicName);

    Set<String> listTopics();

    Config getTopicConfiguration(String topicName);

    void updateTopicConfiguration(String topicName, Map<String, String> configsToUpdate);

    // getStatusForGroup(String groupId, String topic) - This can be composed from describeConsumerGroup, getConsumerLag
    // getStatusForTopic(String topic) - This can be composed from describeTopic and iterating consumer groups

    // Synchronous Wrappers (Example of how one might be declared in a concrete class or extension interface)
    /*
    default void createTopic(TopicOpts topicOpts, String topicName, Duration timeout)
        throws TopicAlreadyExistsException, KafkaAdminServiceException, KafkaOperationTimeoutException, KafkaInterruptedException {
        try {
            createTopicAsync(topicOpts, topicName).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaInterruptedException("Topic creation was interrupted for: " + topicName, e);
        } catch (TimeoutException e) {
            throw new KafkaOperationTimeoutException("Timeout during topic creation for: " + topicName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof TopicAlreadyExistsException) throw (TopicAlreadyExistsException) cause;
            if (cause instanceof KafkaAdminServiceException) throw (KafkaAdminServiceException) cause;
            throw new KafkaAdminServiceException("Error creating topic: " + topicName, cause);
        }
    }
    */
}