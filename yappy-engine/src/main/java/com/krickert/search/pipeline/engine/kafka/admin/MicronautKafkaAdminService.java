package com.krickert.search.pipeline.engine.kafka.admin;


import com.krickert.search.pipeline.engine.kafka.admin.config.KafkaAdminServiceConfig;
import com.krickert.search.pipeline.engine.kafka.admin.exceptions.*;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;

@Singleton
public class MicronautKafkaAdminService implements KafkaAdminService {

    private final AdminClient adminClient;
    private final KafkaAdminServiceConfig config;
    private final TaskScheduler taskScheduler; // Micronaut's TaskScheduler

    public MicronautKafkaAdminService(AdminClient adminClient,
                                      KafkaAdminServiceConfig config,
                                      TaskScheduler taskScheduler) { // Injected
        this.adminClient = adminClient;
        this.config = config;
        this.taskScheduler = taskScheduler;
    }

    // --- Helper to convert KafkaFuture to CompletableFuture ---
    private <T> CompletableFuture<T> toCompletableFuture(KafkaFuture<T> kafkaFuture) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        kafkaFuture.whenComplete((result, error) -> {
            if (error != null) {
                completableFuture.completeExceptionally(mapKafkaException(error));
            } else {
                completableFuture.complete(result);
            }
        });
        return completableFuture;
    }

    // --- Helper to map Kafka-specific exceptions to custom exceptions ---
    private Throwable mapKafkaException(Throwable kafkaError) {
        Throwable cause = (kafkaError instanceof CompletionException || kafkaError instanceof ExecutionException) && kafkaError.getCause() != null ? kafkaError.getCause() : kafkaError;

        if (cause instanceof TopicExistsException) {
            return new TopicAlreadyExistsException(((TopicExistsException) cause).getMessage(), cause);
        } else if (cause instanceof UnknownTopicOrPartitionException) {
            return new TopicNotFoundException("Topic or partition not found: " + cause.getMessage(), cause);
        } else if (cause instanceof GroupIdNotFoundException) {
            return new ConsumerGroupNotFoundException(((GroupIdNotFoundException)cause).getMessage(), cause);
        } else if (cause instanceof org.apache.kafka.common.errors.TimeoutException) { // Kafka client's timeout
            return new KafkaOperationTimeoutException("Kafka operation timed out: " + cause.getMessage(), cause);
        } else if (cause instanceof org.apache.kafka.common.errors.SecurityDisabledException ||
                   cause instanceof org.apache.kafka.common.errors.SaslAuthenticationException ||
                   cause instanceof org.apache.kafka.common.errors.TopicAuthorizationException) {
            return new KafkaSecurityException("Kafka security error: " + cause.getMessage(), cause);
        }
        // Fallback
        return new KafkaAdminServiceException("Kafka admin operation failed: " + cause.getMessage(), cause);
    }


    // --- Topic Management (Asynchronous) ---

    @Override
    public CompletableFuture<Void> createTopicAsync(TopicOpts topicOpts, String topicName) {
        List<String> policyNames = topicOpts.cleanupPolicies().stream()
            .map(CleanupPolicy::getPolicyName)
            .collect(Collectors.toList());
        String cleanupPolicyValue = String.join(",", policyNames);

        Map<String, String> configs = new HashMap<>(topicOpts.additionalConfigs());
        configs.put(org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG, cleanupPolicyValue);
        topicOpts.retentionMs().ifPresent(ms -> configs.put(org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG, String.valueOf(ms)));
        topicOpts.retentionBytes().ifPresent(bytes -> configs.put(org.apache.kafka.common.config.TopicConfig.RETENTION_BYTES_CONFIG, String.valueOf(bytes)));
        topicOpts.compressionType().ifPresent(ct -> configs.put(org.apache.kafka.common.config.TopicConfig.COMPRESSION_TYPE_CONFIG, ct));
        topicOpts.minInSyncReplicas().ifPresent(misr -> configs.put(org.apache.kafka.common.config.TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(misr)));

        NewTopic newTopic = new NewTopic(topicName, topicOpts.partitions(), topicOpts.replicationFactor())
            .configs(configs);

        CreateTopicsResult result = adminClient.createTopics(Collections.singleton(newTopic));
        return toCompletableFuture(result.all());
    }

    @Override
    public CompletableFuture<Void> deleteTopicAsync(String topicName) {
        DeleteTopicsResult result = adminClient.deleteTopics(Collections.singleton(topicName));
        return toCompletableFuture(result.all());
    }

    @Override
    public CompletableFuture<Boolean> doesTopicExistAsync(String topicName) {
        return toCompletableFuture(adminClient.listTopics().names())
            .thenApply(names -> names.contains(topicName))
            .exceptionally(throwable -> {
                 // If listing topics itself fails, wrap it in our service exception
                 throw new KafkaAdminServiceException("Failed to list topics to check existence for: " + topicName, throwable);
            });
    }

    @Override
    public CompletableFuture<Void> recreateTopicAsync(TopicOpts topicOpts, String topicName) {
        // Simplified approach: First check if topic exists, then delete it if it does, then create a new one
        return doesTopicExistAsync(topicName)
            .thenCompose(exists -> {
                if (exists) {
                    // If topic exists, delete it first
                    return deleteTopicAsync(topicName)
                        .thenCompose(v -> {
                            // Wait a bit to ensure the topic is fully deleted
                            CompletableFuture<Void> delayFuture = new CompletableFuture<>();
                            taskScheduler.schedule(Duration.ofSeconds(2), () -> delayFuture.complete(null));
                            return delayFuture;
                        })
                        .thenCompose(v -> createTopicAsync(topicOpts, topicName))
                        .exceptionally(ex -> {
                            // If deletion fails with TopicNotFoundException, it's already gone, so just create
                            if (ex instanceof TopicNotFoundException) {
                                try {
                                    return createTopicAsync(topicOpts, topicName).join();
                                } catch (Exception e) {
                                    throw new CompletionException(e);
                                }
                            }
                            throw new CompletionException(ex);
                        });
                } else {
                    // If topic doesn't exist, just create it
                    return createTopicAsync(topicOpts, topicName);
                }
            })
            .exceptionally(ex -> {
                // If checking existence fails, try to create anyway
                if (ex instanceof KafkaAdminServiceException) {
                    try {
                        return createTopicAsync(topicOpts, topicName).join();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                }
                throw new CompletionException(ex);
            });
    }

    private CompletableFuture<Void> deleteTopicIfExistsAsync(String topicName) {
        return doesTopicExistAsync(topicName).thenComposeAsync(exists -> {
            if (exists) {
                return deleteTopicAsync(topicName);
            }
            // Topic doesn't exist, deletion step is considered successful (idempotent)
            return CompletableFuture.completedFuture(null);
        }, ForkJoinPool.commonPool());
    }

    private CompletableFuture<Void> pollUntilTopicDeleted(String topicName, Duration timeout, Duration interval) {
        CompletableFuture<Void> pollingFuture = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();

        // Using a recursive-like structure with the scheduler
        Runnable pollTask = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - startTime > timeout.toMillis()) {
                    pollingFuture.completeExceptionally(
                        new KafkaOperationTimeoutException("Timeout (" + timeout.getSeconds() + "s) waiting for topic '" + topicName + "' to be deleted.")
                    );
                    return;
                }

                doesTopicExistAsync(topicName).whenComplete((exists, error) -> {
                    if (error != null) {
                        // If doesTopicExistAsync itself fails critically, we might want to stop polling and fail.
                        // Example: if it's not a KafkaAdminServiceException (already wrapped) or some other unexpected error.
                        // For now, we assume mapKafkaException in toCompletableFuture handles most Kafka errors.
                        // If error is about failing to list topics, it might be a transient issue or cluster problem.
                        // Let's log it and retry, but if it persists, the outer timeout will catch it.
                        // System.err.println("Error during topic existence check for '" + topicName + "' in poll: " + error.getMessage());
                        // For now, we will retry on any error during the poll check, relying on the overall timeout.
                        taskScheduler.schedule(interval, this); // Schedule next poll
                        return;
                    }

                    if (!exists) {
                        pollingFuture.complete(null); // Topic is deleted
                    } else {
                        taskScheduler.schedule(interval, this); // Poll again
                    }
                });
            }
        };
        // Schedule the first poll attempt
        taskScheduler.schedule(Duration.ZERO, pollTask);
        return pollingFuture;
    }


    @Override
    public CompletableFuture<TopicDescription> describeTopicAsync(String topicName) {
        return toCompletableFuture(adminClient.describeTopics(Collections.singleton(topicName)).allTopicNames())
            .thenApply(map -> {
                TopicDescription description = map.get(topicName);
                if (description == null) {
                    // This path should ideally be covered by mapKafkaException if UnknownTopicOrPartitionException occurs
                    throw new TopicNotFoundException(topicName);
                }
                return description;
            });
    }

    @Override
    public CompletableFuture<Set<String>> listTopicsAsync() {
        return toCompletableFuture(adminClient.listTopics().names());
    }

    @Override
    public CompletableFuture<Config> getTopicConfigurationAsync(String topicName) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        return toCompletableFuture(adminClient.describeConfigs(Collections.singleton(resource)).all())
            .thenApply(configsMap -> {
                Config config = configsMap.get(resource);
                if (config == null) {
                    // This case should ideally be caught if AdminClient throws UnknownTopicOrPartitionException,
                    // which gets mapped to TopicNotFoundException. This is a safeguard.
                    throw new TopicNotFoundException(topicName + " (or its configuration not found)");
                }
                return config;
            });
    }

    @Override
    public CompletableFuture<Void> updateTopicConfigurationAsync(String topicName, Map<String, String> configsToUpdate) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topicName);
        List<AlterConfigOp> alterOps = configsToUpdate.entrySet().stream()
            .map(entry -> new AlterConfigOp(new ConfigEntry(entry.getKey(), entry.getValue()), AlterConfigOp.OpType.SET))
            .collect(Collectors.toList());

        Map<ConfigResource, Collection<AlterConfigOp>> configs = Collections.singletonMap(resource, alterOps);
        return toCompletableFuture(adminClient.incrementalAlterConfigs(configs).all());
    }

    // --- Consumer Group Management (Asynchronous - Stubs for now for Step 1 focus) ---
    @Override
    public CompletableFuture<Void> resetConsumerGroupOffsetsAsync(String groupId, String topicName, OffsetResetParameters params) {
        // Validate inputs
        if (groupId == null || groupId.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Group ID cannot be null or blank"));
        }
        if (topicName == null || topicName.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Topic name cannot be null or blank"));
        }

        // Create a CompletableFuture to return
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();

        try {
            // Get topic partitions
            DescribeTopicsResult topicResult = adminClient.describeTopics(Collections.singleton(topicName));
            KafkaFuture<Map<String, TopicDescription>> topicFuture = topicResult.allTopicNames();

            topicFuture.whenComplete((topicDescriptions, topicEx) -> {
                if (topicEx != null) {
                    resultFuture.completeExceptionally(
                            new TopicNotFoundException("Failed to describe topic: " + topicName, topicEx));
                    return;
                }

                TopicDescription topicDescription = topicDescriptions.get(topicName);
                if (topicDescription == null) {
                    resultFuture.completeExceptionally(
                            new TopicNotFoundException("Topic not found: " + topicName));
                    return;
                }

                // Create list of topic partitions
                List<TopicPartition> partitions = topicDescription.partitions().stream()
                        .map(partitionInfo -> new TopicPartition(topicName, partitionInfo.partition()))
                        .collect(Collectors.toList());

                // Handle different reset strategies
                switch (params.getStrategy()) {
                    case EARLIEST:
                        resetToEarliest(groupId, partitions, resultFuture);
                        break;
                    case LATEST:
                        resetToLatest(groupId, partitions, resultFuture);
                        break;
                    case TO_TIMESTAMP:
                        resetToTimestamp(groupId, partitions, params.getTimestamp(), resultFuture);
                        break;
                    case TO_SPECIFIC_OFFSETS:
                        resetToSpecificOffsets(groupId, params.getSpecificOffsets(), resultFuture);
                        break;
                    default:
                        resultFuture.completeExceptionally(
                                new IllegalArgumentException("Unsupported reset strategy: " + params.getStrategy()));
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(
                    new KafkaAdminServiceException("Error resetting consumer group offsets", e));
        }

        return resultFuture;
    }

    private void resetToEarliest(String groupId, List<TopicPartition> partitions, CompletableFuture<Void> resultFuture) {
        Map<TopicPartition, OffsetSpec> offsetSpecs = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));

        // Get beginning offsets for all partitions
        adminClient.listOffsets(offsetSpecs)
                .all()
                .whenComplete((offsets, ex) -> {
                    if (ex != null) {
                        resultFuture.completeExceptionally(
                                new KafkaAdminServiceException("Failed to get earliest offsets", ex));
                        return;
                    }

                    Map<TopicPartition, Long> resetOffsets = new HashMap<>();
                    offsets.forEach((tp, offsetInfo) -> resetOffsets.put(tp, offsetInfo.offset()));

                    // Alter consumer group offsets
                    alterConsumerGroupOffsets(groupId, resetOffsets, resultFuture);
                });
    }

    private void resetToLatest(String groupId, List<TopicPartition> partitions, CompletableFuture<Void> resultFuture) {
        Map<TopicPartition, OffsetSpec> offsetSpecs = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));

        // Get end offsets for all partitions
        adminClient.listOffsets(offsetSpecs)
                .all()
                .whenComplete((offsets, ex) -> {
                    if (ex != null) {
                        resultFuture.completeExceptionally(
                                new KafkaAdminServiceException("Failed to get latest offsets", ex));
                        return;
                    }

                    Map<TopicPartition, Long> resetOffsets = new HashMap<>();
                    offsets.forEach((tp, offsetInfo) -> resetOffsets.put(tp, offsetInfo.offset()));

                    // Alter consumer group offsets
                    alterConsumerGroupOffsets(groupId, resetOffsets, resultFuture);
                });
    }

    private void resetToTimestamp(String groupId, List<TopicPartition> partitions, Long timestamp, 
                                 CompletableFuture<Void> resultFuture) {
        if (timestamp == null) {
            resultFuture.completeExceptionally(
                    new IllegalArgumentException("Timestamp cannot be null for TO_TIMESTAMP strategy"));
            return;
        }

        Map<TopicPartition, OffsetSpec> offsetSpecs = partitions.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.forTimestamp(timestamp)));

        // Get offsets for the specified timestamp
        adminClient.listOffsets(offsetSpecs)
                .all()
                .whenComplete((offsets, ex) -> {
                    if (ex != null) {
                        resultFuture.completeExceptionally(
                                new KafkaAdminServiceException("Failed to get offsets for timestamp", ex));
                        return;
                    }

                    Map<TopicPartition, Long> resetOffsets = new HashMap<>();

                    // Process each partition
                    CompletableFuture<?>[] futures = new CompletableFuture<?>[partitions.size()];
                    AtomicInteger index = new AtomicInteger(0);

                    for (TopicPartition tp : partitions) {
                        ListOffsetsResult.ListOffsetsResultInfo offsetInfo = offsets.get(tp);

                        if (offsetInfo != null && offsetInfo.offset() >= 0) {
                            // If we found a valid offset for this timestamp, use it
                            resetOffsets.put(tp, offsetInfo.offset());
                            futures[index.getAndIncrement()] = CompletableFuture.completedFuture(null);
                        } else {
                            // If no offset found for timestamp, use latest
                            CompletableFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> latestFuture = 
                                    toCompletableFuture(adminClient.listOffsets(
                                            Collections.singletonMap(tp, OffsetSpec.latest())).all());

                            futures[index.getAndIncrement()] = latestFuture.thenAccept(latestOffsets -> {
                                ListOffsetsResult.ListOffsetsResultInfo latestInfo = latestOffsets.get(tp);
                                if (latestInfo != null) {
                                    resetOffsets.put(tp, latestInfo.offset());
                                }
                            });
                        }
                    }

                    // Wait for all partition offset lookups to complete
                    CompletableFuture.allOf(futures).whenComplete((v, allEx) -> {
                        if (allEx != null) {
                            resultFuture.completeExceptionally(
                                    new KafkaAdminServiceException("Failed to get offsets for some partitions", allEx));
                            return;
                        }

                        // Alter consumer group offsets
                        alterConsumerGroupOffsets(groupId, resetOffsets, resultFuture);
                    });
                });
    }

    private void resetToSpecificOffsets(String groupId, Map<TopicPartition, Long> specificOffsets, 
                                       CompletableFuture<Void> resultFuture) {
        if (specificOffsets == null || specificOffsets.isEmpty()) {
            resultFuture.completeExceptionally(
                    new IllegalArgumentException("Specific offsets cannot be null or empty for TO_SPECIFIC_OFFSETS strategy"));
            return;
        }

        // Directly alter consumer group offsets with the provided specific offsets
        alterConsumerGroupOffsets(groupId, specificOffsets, resultFuture);
    }

    private void alterConsumerGroupOffsets(String groupId, Map<TopicPartition, Long> offsets, 
                                          CompletableFuture<Void> resultFuture) {
        try {
            // Convert from Map<TopicPartition, Long> to Map<TopicPartition, OffsetAndMetadata>
            Map<TopicPartition, OffsetAndMetadata> offsetsAndMetadata = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : offsets.entrySet()) {
                offsetsAndMetadata.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
            }

            // Call the AdminClient API to alter consumer group offsets
            AlterConsumerGroupOffsetsResult alterResult = adminClient.alterConsumerGroupOffsets(groupId, offsetsAndMetadata);

            // Handle the result
            alterResult.all().whenComplete((v, ex) -> {
                if (ex != null) {
                    resultFuture.completeExceptionally(
                            new KafkaAdminServiceException("Failed to alter consumer group offsets", ex));
                } else {
                    resultFuture.complete(null);
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(
                    new KafkaAdminServiceException("Error altering consumer group offsets", e));
        }
    }

    @Override
    public CompletableFuture<ConsumerGroupDescription> describeConsumerGroupAsync(String groupId) {
        // To be implemented in Step 2
        CompletableFuture<ConsumerGroupDescription> future = new CompletableFuture<>();
        future.completeExceptionally(new CompletionException(new UnsupportedOperationException("describeConsumerGroupAsync not implemented yet.")));
        return future;
    }

    @Override
    public CompletableFuture<Set<String>> listConsumerGroupsAsync() {
        // To be implemented in Step 2
        CompletableFuture<Set<String>> future = new CompletableFuture<>();
        future.completeExceptionally(new CompletionException(new UnsupportedOperationException("listConsumerGroupsAsync not implemented yet.")));
        return future;
    }

    @Override
    public CompletableFuture<Void> deleteConsumerGroupAsync(String groupId) {
        // To be implemented in Step 2
        CompletableFuture<Void> future = new CompletableFuture<>();
        future.completeExceptionally(new CompletionException(new UnsupportedOperationException("deleteConsumerGroupAsync not implemented yet.")));
        return future;
    }

    // --- Status & Lag Monitoring (Asynchronous - Stubs for now for Step 1 focus) ---
    @Override
    public CompletableFuture<Map<TopicPartition, Long>> getConsumerLagPerPartitionAsync(String groupId, String topicName) {
        // To be implemented in Step 2
        return CompletableFuture.failedFuture(new UnsupportedOperationException("getConsumerLagPerPartitionAsync not implemented yet."));
    }

    @Override
    public CompletableFuture<Long> getTotalConsumerLagAsync(String groupId, String topicName) {
        // To be implemented in Step 2
        return CompletableFuture.failedFuture(new UnsupportedOperationException("getTotalConsumerLagAsync not implemented yet."));
    }

    // --- Synchronous Wrappers (for methods covered in Step 1) ---

    @Override
    public void createTopic(TopicOpts topicOpts, String topicName) {
        waitFor(createTopicAsync(topicOpts, topicName), config.getRequestTimeout(), "createTopic: " + topicName);
    }

    @Override
    public void deleteTopic(String topicName) {
        waitFor(deleteTopicAsync(topicName), config.getRequestTimeout(), "deleteTopic: " + topicName);
    }

    @Override
    public boolean doesTopicExist(String topicName) {
        return waitFor(doesTopicExistAsync(topicName), config.getRequestTimeout(), "doesTopicExist: " + topicName);
    }

    @Override
    public void recreateTopic(TopicOpts topicOpts, String topicName) {
        // recreateTopicAsync has internal polling with its own timeout (recreatePollTimeout).
        // The requestTimeout for the synchronous wrapper should accommodate the entire operation.
        // Adding them provides a generous upper bound.
        Duration combinedTimeout = config.getRequestTimeout().plus(config.getRecreatePollTimeout());
        waitFor(recreateTopicAsync(topicOpts, topicName), combinedTimeout, "recreateTopic: " + topicName);
    }

    @Override
    public TopicDescription describeTopic(String topicName) {
        return waitFor(describeTopicAsync(topicName), config.getRequestTimeout(), "describeTopic: " + topicName);
    }

    @Override
    public Set<String> listTopics() {
        return waitFor(listTopicsAsync(), config.getRequestTimeout(), "listTopics");
    }

    @Override
    public Config getTopicConfiguration(String topicName) {
        return waitFor(getTopicConfigurationAsync(topicName), config.getRequestTimeout(), "getTopicConfiguration: " + topicName);
    }

    @Override
    public void updateTopicConfiguration(String topicName, Map<String, String> configsToUpdate) {
        waitFor(updateTopicConfigurationAsync(topicName, configsToUpdate), config.getRequestTimeout(), "updateTopicConfiguration: " + topicName);
    }

    @Override
    public CompletableFuture<Integer> getAvailableBrokerCountAsync() {
        return toCompletableFuture(adminClient.describeCluster().nodes())
            .thenApply(nodes -> nodes.size());
    }

    @Override
    public int getAvailableBrokerCount() {
        return waitFor(getAvailableBrokerCountAsync(), config.getRequestTimeout(), "getAvailableBrokerCount");
    }

    // ... synchronous wrappers for consumer group and lag methods will be added in subsequent steps ...

    private <T> T waitFor(CompletableFuture<T> future, Duration timeout, String operationDescription) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaInterruptedException("Operation '" + operationDescription + "' was interrupted.", e);
        } catch (TimeoutException e) { // CompletableFuture's TimeoutException
            throw new KafkaOperationTimeoutException("Operation '" + operationDescription + "' timed out after " + timeout.getSeconds() + "s", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof KafkaAdminServiceException) {
                // If it's already one of our specific exceptions (mapped by toCompletableFuture), rethrow it.
                throw (KafkaAdminServiceException) cause;
            }
            // Otherwise, wrap it as a generic service exception.
            throw new KafkaAdminServiceException("Error during operation '" + operationDescription + "': " + (cause != null ? cause.getMessage() : "Unknown error"), cause);
        }
    }
}
