package com.krickert.search.orchestrator.kafka.admin;

import com.krickert.search.orchestrator.kafka.admin.config.PipelineKafkaTopicConfig;
import jakarta.inject.Singleton;
import org.apache.kafka.common.config.TopicConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Implementation of PipelineKafkaTopicService that uses KafkaAdminService to create and manage Kafka topics for pipeline steps.
 */
@Singleton
public class MicronautPipelineKafkaTopicService implements PipelineKafkaTopicService {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautPipelineKafkaTopicService.class);

    private final KafkaAdminService kafkaAdminService;
    private final PipelineKafkaTopicConfig topicConfig;

    public MicronautPipelineKafkaTopicService(KafkaAdminService kafkaAdminService, PipelineKafkaTopicConfig topicConfig) {
        this.kafkaAdminService = kafkaAdminService;
        this.topicConfig = topicConfig;
    }

    @Override
    public CompletableFuture<Void> createTopicAsync(String pipelineName, String stepName, TopicType topicType) {
        String topicName = generateTopicName(pipelineName, stepName, topicType);
        LOG.info("Creating Kafka topic: {}", topicName);

        TopicOpts topicOpts = createTopicOpts();

        return kafkaAdminService.createTopicAsync(topicOpts, topicName);
    }

    @Override
    public CompletableFuture<Void> createAllTopicsAsync(String pipelineName, String stepName) {
        LOG.info("Creating all Kafka topics for pipeline: {}, step: {}", pipelineName, stepName);

        List<CompletableFuture<Void>> futures = Arrays.stream(TopicType.values())
                .map(topicType -> createTopicAsync(pipelineName, stepName, topicType))
                .collect(Collectors.toList());

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public String generateTopicName(String pipelineName, String stepName, TopicType topicType) {
        return String.format("pipeline.%s.step.%s.%s", pipelineName, stepName, topicType.getSuffix());
    }

    @Override
    public CompletableFuture<List<String>> listTopicsForStepAsync(String pipelineName, String stepName) {
        String topicPrefix = String.format("pipeline.%s.step.%s.", pipelineName, stepName);

        return kafkaAdminService.listTopicsAsync()
                .thenApply(topics -> topics.stream()
                        .filter(topic -> topic.startsWith(topicPrefix))
                        .collect(Collectors.toList()));
    }

    @Override
    public void createTopic(String pipelineName, String stepName, TopicType topicType) {
        String topicName = generateTopicName(pipelineName, stepName, topicType);
        LOG.info("Creating Kafka topic (sync): {}", topicName);

        TopicOpts topicOpts = createTopicOpts();

        kafkaAdminService.createTopic(topicOpts, topicName);
    }

    @Override
    public void createAllTopics(String pipelineName, String stepName) {
        LOG.info("Creating all Kafka topics (sync) for pipeline: {}, step: {}", pipelineName, stepName);

        for (TopicType topicType : TopicType.values()) {
            createTopic(pipelineName, stepName, topicType);
        }
    }

    @Override
    public List<String> listTopicsForStep(String pipelineName, String stepName) {
        String topicPrefix = String.format("pipeline.%s.step.%s.", pipelineName, stepName);

        return kafkaAdminService.listTopics().stream()
                .filter(topic -> topic.startsWith(topicPrefix))
                .collect(Collectors.toList());
    }

    /**
     * Creates a TopicOpts object with the configured settings.
     * Automatically adjusts the replication factor based on the number of available brokers.
     * 
     * @return TopicOpts with the configured settings
     */
    private TopicOpts createTopicOpts() {
        short configuredReplicationFactor = topicConfig.getReplicationFactor();

        // Get the number of available brokers
        int availableBrokers = kafkaAdminService.getAvailableBrokerCount();

        // Adjust replication factor if necessary
        short adjustedReplicationFactor = (short) Math.min(configuredReplicationFactor, availableBrokers);

        LOG.info("Adjusting replication factor from {} to {} based on {} available brokers", 
                configuredReplicationFactor, adjustedReplicationFactor, availableBrokers);

        return createTopicOpts(adjustedReplicationFactor);
    }

    /**
     * Creates a TopicOpts object with the configured settings and a specific replication factor.
     * 
     * @param replicationFactor The replication factor to use
     * @return TopicOpts with the configured settings
     */
    private TopicOpts createTopicOpts(short replicationFactor) {
        List<CleanupPolicy> cleanupPolicies = new ArrayList<>();
        if (topicConfig.isCompactEnabled()) {
            cleanupPolicies.add(CleanupPolicy.COMPACT);
        }
        cleanupPolicies.add(CleanupPolicy.DELETE);

        Map<String, String> additionalConfigs = new HashMap<>();
        additionalConfigs.put(TopicConfig.MAX_MESSAGE_BYTES_CONFIG, String.valueOf(topicConfig.getMaxMessageBytes()));

        return new TopicOpts(
                topicConfig.getPartitions(),
                replicationFactor,
                cleanupPolicies,
                Optional.of(topicConfig.getRetentionMs()),
                Optional.empty(), // No retention bytes limit
                Optional.of(topicConfig.getCompressionType()),
                Optional.empty(), // No min in-sync replicas
                additionalConfigs
        );
    }

    /**
     * Creates a TopicOpts object with a specific replication factor, without automatic adjustment.
     * This is useful for testing the replication factor error case.
     * 
     * @param replicationFactor The replication factor to use
     * @return TopicOpts with the configured settings and the specified replication factor
     */
    public TopicOpts createTopicOptsWithoutAdjustment(short replicationFactor) {
        return createTopicOpts(replicationFactor);
    }
}
