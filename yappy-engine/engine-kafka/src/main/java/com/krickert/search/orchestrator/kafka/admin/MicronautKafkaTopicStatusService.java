package com.krickert.search.orchestrator.kafka.admin;


import com.krickert.search.orchestrator.kafka.admin.exceptions.KafkaAdminServiceException;
import com.krickert.search.orchestrator.kafka.admin.model.KafkaTopicStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Singleton;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Implementation of KafkaTopicStatusService that uses KafkaAdminService to retrieve status information about Kafka topics.
 * This implementation also integrates with Micrometer for tracking timings and stats.
 * 
 * <p>This implementation:</p>
 * <ul>
 *   <li>Uses KafkaAdminService to retrieve information about Kafka topics and consumer groups</li>
 *   <li>Integrates with Micrometer to track timings and stats</li>
 *   <li>Provides both asynchronous and synchronous methods for retrieving topic status information</li>
 *   <li>Caches listener status information to avoid repeated calls to Kafka</li>
 * </ul>
 * 
 * <p>The following metrics are tracked:</p>
 * <ul>
 *   <li>{@code kafka.topic.status.time}: Time taken to retrieve topic status</li>
 *   <li>{@code kafka.topic.status.error}: Count of errors retrieving topic status</li>
 *   <li>{@code kafka.topic.partition.count}: Number of partitions in a topic</li>
 *   <li>{@code kafka.topic.replication.factor}: Replication factor of a topic</li>
 *   <li>{@code kafka.topic.largest.offset}: Largest offset in a topic</li>
 *   <li>{@code kafka.topic.consumer.lag}: Consumer lag for a topic and consumer group</li>
 * </ul>
 */
@Singleton
public class MicronautKafkaTopicStatusService implements KafkaTopicStatusService {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaTopicStatusService.class);
    private static final String METRIC_PREFIX = "kafka.topic.";

    private final KafkaAdminService kafkaAdminService;
    private final MeterRegistry meterRegistry;

    // Cache of listener status by consumer group ID
    private final Map<String, KafkaTopicStatus.ListenerStatus> listenerStatusCache = new ConcurrentHashMap<>();

    public MicronautKafkaTopicStatusService(KafkaAdminService kafkaAdminService, MeterRegistry meterRegistry) {
        this.kafkaAdminService = kafkaAdminService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public CompletableFuture<KafkaTopicStatus> getTopicStatusAsync(String topicName) {
        Timer.Sample sample = Timer.start(meterRegistry);

        return kafkaAdminService.describeTopicAsync(topicName)
            .thenCompose(topicDescription -> {
                // Get topic configuration
                return kafkaAdminService.getTopicConfigurationAsync(topicName)
                    .thenCompose(config -> {
                        // Get all consumer groups
                        return kafkaAdminService.listConsumerGroupsAsync()
                            .thenCompose(consumerGroups -> {
                                // Find consumer groups that are consuming from this topic
                                List<CompletableFuture<Map.Entry<String, Long>>> lagFutures = new ArrayList<>();

                                for (String groupId : consumerGroups) {
                                    CompletableFuture<Map.Entry<String, Long>> lagFuture = kafkaAdminService.getTotalConsumerLagAsync(groupId, topicName)
                                        .thenApply(lag -> Map.entry(groupId, lag))
                                        .exceptionally(ex -> {
                                            // If the consumer group is not consuming from this topic, ignore it
                                            LOG.debug("Consumer group {} is not consuming from topic {}", groupId, topicName);
                                            return Map.entry(groupId, 0L);
                                        });

                                    lagFutures.add(lagFuture);
                                }

                                return CompletableFuture.allOf(lagFutures.toArray(new CompletableFuture[0]))
                                    .thenApply(v -> {
                                        // Find the consumer group with the largest lag
                                        Optional<Map.Entry<String, Long>> maxLagEntry = lagFutures.stream()
                                            .map(CompletableFuture::join)
                                            .filter(entry -> entry.getValue() > 0)
                                            .max(Map.Entry.comparingByValue());

                                        if (maxLagEntry.isPresent()) {
                                            String consumerGroupId = maxLagEntry.get().getKey();
                                            long totalLag = maxLagEntry.get().getValue();

                                            // Get detailed information for this consumer group
                                            return kafkaAdminService.getConsumerLagPerPartitionAsync(consumerGroupId, topicName)
                                                .thenApply(lagPerPartition -> {
                                                    // Build the KafkaTopicStatus
                                                    return buildTopicStatus(topicName, topicDescription, config, consumerGroupId, lagPerPartition, totalLag);
                                                });
                                        } else {
                                            // No consumer groups consuming from this topic
                                            return CompletableFuture.completedFuture(
                                                buildTopicStatus(topicName, topicDescription, config, null, null, 0L)
                                            );
                                        }
                                    })
                                    .thenCompose(future -> future);
                            });
                    });
            })
            .whenComplete((result, ex) -> {
                Timer timer = Timer.builder(METRIC_PREFIX + "status.time")
                    .description("Time taken to retrieve topic status")
                    .tags(Tags.of("topic", topicName))
                    .register(meterRegistry);
                sample.stop(timer);

                if (ex != null) {
                    LOG.error("Error retrieving status for topic {}: {}", topicName, ex.getMessage());
                    meterRegistry.counter(METRIC_PREFIX + "status.error", Tags.of("topic", topicName)).increment();
                } else {
                    LOG.debug("Retrieved status for topic {}: {}", topicName, result);

                    // Record metrics
                    meterRegistry.gauge(METRIC_PREFIX + "partition.count", 
                        Tags.of("topic", topicName), result.partitionCount());
                    meterRegistry.gauge(METRIC_PREFIX + "replication.factor", 
                        Tags.of("topic", topicName), result.replicationFactor());

                    if (result.largestOffset() > 0) {
                        meterRegistry.gauge(METRIC_PREFIX + "largest.offset", 
                            Tags.of("topic", topicName), result.largestOffset());
                    }

                    if (result.totalLag() > 0) {
                        meterRegistry.gauge(METRIC_PREFIX + "consumer.lag", 
                            Tags.of("topic", topicName, "consumer_group", result.consumerGroupId()), 
                            result.totalLag());
                    }
                }
            });
    }

    @Override
    public CompletableFuture<KafkaTopicStatus> getTopicStatusForConsumerGroupAsync(String topicName, String consumerGroupId) {
        Timer.Sample sample = Timer.start(meterRegistry);

        return kafkaAdminService.describeTopicAsync(topicName)
            .thenCompose(topicDescription -> {
                // Get topic configuration
                return kafkaAdminService.getTopicConfigurationAsync(topicName)
                    .thenCompose(config -> {
                        // Get consumer group lag
                        return kafkaAdminService.getTotalConsumerLagAsync(consumerGroupId, topicName)
                            .thenCompose(totalLag -> {
                                // Get detailed lag information
                                return kafkaAdminService.getConsumerLagPerPartitionAsync(consumerGroupId, topicName)
                                    .thenApply(lagPerPartition -> {
                                        // Build the KafkaTopicStatus
                                        return buildTopicStatus(topicName, topicDescription, config, consumerGroupId, lagPerPartition, totalLag);
                                    });
                            });
                    });
            })
            .whenComplete((result, ex) -> {
                Timer timer = Timer.builder(METRIC_PREFIX + "status.time")
                    .description("Time taken to retrieve topic status for consumer group")
                    .tags(Tags.of("topic", topicName, "consumer_group", consumerGroupId))
                    .register(meterRegistry);
                sample.stop(timer);

                if (ex != null) {
                    LOG.error("Error retrieving status for topic {} and consumer group {}: {}", 
                        topicName, consumerGroupId, ex.getMessage());
                    meterRegistry.counter(METRIC_PREFIX + "status.error", 
                        Tags.of("topic", topicName, "consumer_group", consumerGroupId)).increment();
                } else {
                    LOG.debug("Retrieved status for topic {} and consumer group {}: {}", 
                        topicName, consumerGroupId, result);

                    // Record metrics
                    if (result.totalLag() > 0) {
                        meterRegistry.gauge(METRIC_PREFIX + "consumer.lag", 
                            Tags.of("topic", topicName, "consumer_group", consumerGroupId), 
                            result.totalLag());
                    }
                }
            });
    }

    @Override
    public CompletableFuture<Map<String, KafkaTopicStatus>> getMultipleTopicStatusAsync(Iterable<String> topicNames) {
        List<String> topicList = StreamSupport.stream(topicNames.spliterator(), false)
            .collect(Collectors.toList());

        List<CompletableFuture<Map.Entry<String, KafkaTopicStatus>>> futures = topicList.stream()
            .map(topicName -> getTopicStatusAsync(topicName)
                .thenApply(status -> Map.entry(topicName, status))
                .exceptionally(ex -> {
                    LOG.error("Error retrieving status for topic {}: {}", topicName, ex.getMessage());
                    return Map.entry(topicName, null);
                }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override
    public CompletableFuture<Map<String, KafkaTopicStatus>> getTopicStatusForMultipleConsumerGroupsAsync(
            String topicName, Iterable<String> consumerGroupIds) {
        List<String> groupList = StreamSupport.stream(consumerGroupIds.spliterator(), false)
            .collect(Collectors.toList());

        List<CompletableFuture<Map.Entry<String, KafkaTopicStatus>>> futures = groupList.stream()
            .map(groupId -> getTopicStatusForConsumerGroupAsync(topicName, groupId)
                .thenApply(status -> Map.entry(groupId, status))
                .exceptionally(ex -> {
                    LOG.error("Error retrieving status for topic {} and consumer group {}: {}", 
                        topicName, groupId, ex.getMessage());
                    return Map.entry(groupId, null);
                }))
            .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(v -> futures.stream()
                .map(CompletableFuture::join)
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    @Override
    public KafkaTopicStatus getTopicStatus(String topicName) {
        try {
            return getTopicStatusAsync(topicName).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Error retrieving status for topic {}: {}", topicName, e.getMessage());
            throw new KafkaAdminServiceException("Error retrieving status for topic " + topicName, e);
        }
    }

    @Override
    public KafkaTopicStatus getTopicStatusForConsumerGroup(String topicName, String consumerGroupId) {
        try {
            return getTopicStatusForConsumerGroupAsync(topicName, consumerGroupId).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Error retrieving status for topic {} and consumer group {}: {}", 
                topicName, consumerGroupId, e.getMessage());
            throw new KafkaAdminServiceException(
                "Error retrieving status for topic " + topicName + " and consumer group " + consumerGroupId, e);
        }
    }

    @Override
    public Map<String, KafkaTopicStatus> getMultipleTopicStatus(Iterable<String> topicNames) {
        try {
            return getMultipleTopicStatusAsync(topicNames).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Error retrieving status for multiple topics: {}", e.getMessage());
            throw new KafkaAdminServiceException("Error retrieving status for multiple topics", e);
        }
    }

    @Override
    public Map<String, KafkaTopicStatus> getTopicStatusForMultipleConsumerGroups(
            String topicName, Iterable<String> consumerGroupIds) {
        try {
            return getTopicStatusForMultipleConsumerGroupsAsync(topicName, consumerGroupIds).get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Error retrieving status for topic {} and multiple consumer groups: {}", 
                topicName, e.getMessage());
            throw new KafkaAdminServiceException(
                "Error retrieving status for topic " + topicName + " and multiple consumer groups", e);
        }
    }

    /**
     * Builds a KafkaTopicStatus object from the given information.
     */
    private KafkaTopicStatus buildTopicStatus(
            String topicName, 
            TopicDescription topicDescription, 
            Config config,
            String consumerGroupId,
            Map<TopicPartition, Long> lagPerPartition,
            long totalLag) {

        // Get partition offsets
        Map<Integer, Long> partitionOffsets = new HashMap<>();
        Map<Integer, Long> consumerGroupOffsets = new HashMap<>();
        Map<Integer, Long> lagByPartition = new HashMap<>();

        // Get the largest offset
        long largestOffset = 0;

        if (lagPerPartition != null) {
            for (Map.Entry<TopicPartition, Long> entry : lagPerPartition.entrySet()) {
                TopicPartition tp = entry.getKey();
                long lag = entry.getValue();

                // Only process partitions for the current topic
                if (tp.topic().equals(topicName)) {
                    int partition = tp.partition();
                    lagByPartition.put(partition, lag);

                    // Get the end offset for this partition
                    // Note: This is an approximation since we don't have direct access to the end offset here
                    // In a real implementation, you would use AdminClient.listOffsets with OffsetSpec.latest()
                    // to get the actual end offsets
                    long endOffset = 0;
                    try {
                        // This is a placeholder - in a real implementation, you would get the actual end offset
                        // For now, we'll use the lag to estimate it
                        endOffset = lag;
                        partitionOffsets.put(partition, endOffset);

                        // Update the largest offset
                        if (endOffset > largestOffset) {
                            largestOffset = endOffset;
                        }

                        // Calculate the consumer group offset
                        long consumerOffset = endOffset - lag;
                        consumerGroupOffsets.put(partition, consumerOffset);
                    } catch (Exception e) {
                        LOG.warn("Error getting end offset for topic {} partition {}: {}", 
                            topicName, partition, e.getMessage());
                    }
                }
            }
        }

        // Get listener status
        KafkaTopicStatus.ListenerStatus listenerStatus = getListenerStatus(consumerGroupId);

        // Get metrics
        Map<String, Double> metrics = getMetricsForTopic(topicName, consumerGroupId);

        // Build the KafkaTopicStatus
        return KafkaTopicStatus.builder()
            .topicName(topicName)
            .isHealthy(true) // Assume healthy unless we have evidence otherwise
            .healthStatus("OK")
            .lastCheckedTime(Instant.now())
            .partitionCount(topicDescription.partitions().size())
            .replicationFactor((short) topicDescription.partitions().get(0).replicas().size())
            .partitionOffsets(partitionOffsets)
            .largestOffset(largestOffset)
            .consumerGroupId(consumerGroupId)
            .consumerGroupOffsets(consumerGroupOffsets)
            .lagPerPartition(lagByPartition)
            .totalLag(totalLag)
            .listenerStatus(listenerStatus)
            .metrics(metrics)
            .build();
    }

    /**
     * Gets the listener status for a consumer group.
     * In a real implementation, this would be determined by checking the consumer group's state
     * and possibly other metrics.
     */
    private KafkaTopicStatus.ListenerStatus getListenerStatus(String consumerGroupId) {
        if (consumerGroupId == null) {
            return KafkaTopicStatus.ListenerStatus.UNKNOWN;
        }

        // Check if we have a cached status
        if (listenerStatusCache.containsKey(consumerGroupId)) {
            return listenerStatusCache.get(consumerGroupId);
        }

        // In a real implementation, you would determine the status based on the consumer group's state
        // For now, we'll just return RECEIVING
        KafkaTopicStatus.ListenerStatus status = KafkaTopicStatus.ListenerStatus.RECEIVING;

        // Cache the status
        listenerStatusCache.put(consumerGroupId, status);

        return status;
    }

    /**
     * Gets metrics for a topic and consumer group from Micrometer.
     */
    private Map<String, Double> getMetricsForTopic(String topicName, String consumerGroupId) {
        Map<String, Double> metrics = new HashMap<>();

        // Add metrics from Micrometer
        // In a real implementation, you would query Micrometer for metrics related to this topic
        // For now, we'll just add some placeholder metrics
        metrics.put("messages_per_second", 10.0);
        metrics.put("bytes_per_second", 1024.0);

        if (consumerGroupId != null) {
            metrics.put("consumer_lag_rate", 0.5);
        }

        return metrics;
    }

    /**
     * Helper class for creating Micrometer tags.
     */
    private static class Tags {
        public static List<Tag> of(String... keyValues) {
            if (keyValues.length % 2 != 0) {
                throw new IllegalArgumentException("Tags must be specified as key-value pairs");
            }

            List<Tag> tags = new ArrayList<>(keyValues.length / 2);
            for (int i = 0; i < keyValues.length; i += 2) {
                tags.add(Tag.of(keyValues[i], keyValues[i + 1]));
            }

            return tags;
        }
    }
}
