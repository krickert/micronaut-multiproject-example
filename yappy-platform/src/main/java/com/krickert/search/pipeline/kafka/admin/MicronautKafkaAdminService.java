package com.krickert.search.pipeline.kafka.admin;

import com.krickert.search.pipeline.kafka.admin.config.KafkaAdminServiceConfig;
import com.krickert.search.pipeline.kafka.admin.exceptions.*;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

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
        CompletableFuture<Void> future = new CompletableFuture<>();

        // Step 1: Delete the topic if it exists
        deleteTopicIfExistsAsync(topicName).whenComplete((deleteResult, deleteError) -> {
            if (deleteError != null) {
                future.completeExceptionally(deleteError);
                return;
            }

            // Step 2: Poll until the topic is deleted
            pollUntilTopicDeleted(topicName, config.getRecreatePollTimeout(), config.getRecreatePollInterval())
                .whenComplete((pollResult, pollError) -> {
                    if (pollError != null) {
                        // If polling fails (e.g., timeout), complete the future exceptionally with that error
                        future.completeExceptionally(pollError);
                        return;
                    }

                    // Step 3: Only if polling succeeds (topic is deleted), create the new topic
                    createTopicAsync(topicOpts, topicName).whenComplete((createResult, createError) -> {
                        if (createError != null) {
                            future.completeExceptionally(createError);
                        } else {
                            future.complete(null);
                        }
                    });
                });
        });

        return future;
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
        // To be implemented in Step 3
        return CompletableFuture.failedFuture(new UnsupportedOperationException("resetConsumerGroupOffsetsAsync not implemented yet."));
    }

    @Override
    public CompletableFuture<ConsumerGroupDescription> describeConsumerGroupAsync(String groupId) {
        // To be implemented in Step 2
        return CompletableFuture.failedFuture(new UnsupportedOperationException("describeConsumerGroupAsync not implemented yet."));
    }

    @Override
    public CompletableFuture<Set<String>> listConsumerGroupsAsync() {
        // To be implemented in Step 2
        return CompletableFuture.failedFuture(new UnsupportedOperationException("listConsumerGroupsAsync not implemented yet."));
    }

    @Override
    public CompletableFuture<Void> deleteConsumerGroupAsync(String groupId) {
        // To be implemented in Step 2
        return CompletableFuture.failedFuture(new UnsupportedOperationException("deleteConsumerGroupAsync not implemented yet."));
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

    // ... synchronous wrappers for consumer group and lag methods will be added in subsequent steps ...

    private <T> T waitFor(CompletableFuture<T> future, Duration timeout, String operationDescription) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaInterruptedException("Operation '" + operationDescription + "' was interrupted.", e);
        } catch (java.util.concurrent.TimeoutException e) { // CompletableFuture's TimeoutException
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
