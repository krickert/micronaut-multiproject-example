package com.krickert.search.orchestrator.kafka.listener;

import com.krickert.search.config.pipeline.event.PipelineClusterConfigChangeEvent;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.orchestrator.kafka.admin.PipelineKafkaTopicService;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.scheduling.annotation.Async;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Listens for pipeline configuration changes and creates Kafka topics for pipeline steps that have Kafka inputs.
 * This ensures that topics exist before the KafkaListenerManager creates listeners for them.
 */
@Singleton
@Requires(property = "kafka.enabled", value = "true")
public class PipelineKafkaTopicCreationListener implements ApplicationEventListener<PipelineClusterConfigChangeEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(PipelineKafkaTopicCreationListener.class);

    private final PipelineKafkaTopicService topicService;
    private final String appClusterName;
    
    /**
     * Track which topics we've already created to avoid redundant creation attempts.
     * Key: "pipelineName:stepName"
     */
    private final Set<String> createdTopicsCache = ConcurrentHashMap.newKeySet();

    @Inject
    public PipelineKafkaTopicCreationListener(
            PipelineKafkaTopicService topicService,
            @Value("${app.config.cluster-name}") String appClusterName) {
        this.topicService = topicService;
        this.appClusterName = appClusterName;
        LOG.info("PipelineKafkaTopicCreationListener initialized for cluster '{}'", this.appClusterName);
    }

    @Override
    @Async // Process events asynchronously to avoid blocking the event publisher
    public void onApplicationEvent(@NonNull PipelineClusterConfigChangeEvent event) {
        LOG.info("Received PipelineClusterConfigChangeEvent for cluster: {}. Current app cluster: {}. Is deletion: {}",
                event.clusterName(), this.appClusterName, event.isDeletion());

        if (!this.appClusterName.equals(event.clusterName())) {
            LOG.debug("Ignoring config update event for cluster '{}' as this listener is for cluster '{}'.",
                    event.clusterName(), this.appClusterName);
            return;
        }

        if (event.isDeletion()) {
            LOG.info("Cluster configuration for '{}' was deleted. Clearing topic cache.", this.appClusterName);
            createdTopicsCache.clear();
            return;
        }

        PipelineClusterConfig clusterConfig = event.newConfig();
        if (clusterConfig == null) {
            LOG.error("Received non-deletion event for cluster '{}' but newConfig is null. This is unexpected.", this.appClusterName);
            return;
        }

        createTopicsForClusterConfig(clusterConfig);
    }

    private void createTopicsForClusterConfig(PipelineClusterConfig clusterConfig) {
        if (clusterConfig.pipelineGraphConfig() == null || clusterConfig.pipelineGraphConfig().pipelines() == null) {
            LOG.debug("No pipelines found in cluster configuration for '{}'", this.appClusterName);
            return;
        }

        Set<String> requiredTopics = new HashSet<>();
        Map<String, PipelineConfig> pipelines = clusterConfig.pipelineGraphConfig().pipelines();
        
        // Identify all pipeline steps that need Kafka topics
        for (PipelineConfig pipeline : pipelines.values()) {
            if (pipeline.pipelineSteps() == null) continue;
            
            for (PipelineStepConfig step : pipeline.pipelineSteps().values()) {
                // Check if this step has Kafka inputs defined
                if (step.kafkaInputs() != null && !step.kafkaInputs().isEmpty()) {
                    String topicKey = generateTopicKey(pipeline.name(), step.stepName());
                    requiredTopics.add(topicKey);
                }
                
                // Also check if the step uses Kafka transport (publishes to Kafka)
                if (step.outputs() != null && !step.outputs().isEmpty()) {
                    boolean hasKafkaOutput = step.outputs().values().stream()
                        .anyMatch(output -> output.transportType() == TransportType.KAFKA);
                    if (hasKafkaOutput) {
                        String topicKey = generateTopicKey(pipeline.name(), step.stepName());
                        requiredTopics.add(topicKey);
                    }
                }
            }
        }

        // Create topics for new pipeline steps
        Set<String> topicsToCreate = requiredTopics.stream()
                .filter(key -> !createdTopicsCache.contains(key))
                .collect(Collectors.toSet());

        if (topicsToCreate.isEmpty()) {
            LOG.debug("No new topics to create for cluster '{}'", this.appClusterName);
            return;
        }

        LOG.info("Creating Kafka topics for {} pipeline steps in cluster '{}'", topicsToCreate.size(), this.appClusterName);
        
        // Create topics asynchronously
        topicsToCreate.forEach(topicKey -> {
            String[] parts = topicKey.split(":");
            String pipelineName = parts[0];
            String stepName = parts[1];
            
            LOG.info("Creating Kafka topics for pipeline '{}', step '{}'", pipelineName, stepName);
            
            // Create all topic types (input, output, error, dead-letter) for the step
            CompletableFuture<Void> future = topicService.createAllTopicsAsync(pipelineName, stepName)
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            LOG.error("Failed to create topics for pipeline '{}', step '{}': {}", 
                                    pipelineName, stepName, error.getMessage(), error);
                        } else {
                            LOG.info("Successfully created all topics for pipeline '{}', step '{}'", 
                                    pipelineName, stepName);
                            createdTopicsCache.add(topicKey);
                        }
                    });
        });
    }

    private String generateTopicKey(String pipelineName, String stepName) {
        return pipelineName + ":" + stepName;
    }
    
    /**
     * Get the number of topics that have been created by this listener.
     * Useful for testing and monitoring.
     */
    public int getCreatedTopicsCount() {
        return createdTopicsCache.size();
    }
    
    /**
     * Clear the cache of created topics. Useful for testing.
     */
    public void clearCreatedTopicsCache() {
        createdTopicsCache.clear();
    }
}