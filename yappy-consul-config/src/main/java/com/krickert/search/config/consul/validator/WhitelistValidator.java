package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Singleton
public class WhitelistValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistValidator.class);

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();

        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping whitelist validation.");
            return errors; // Or add "PipelineClusterConfig is null." as an error
        }

        LOG.debug("Performing whitelist validation for cluster: {}", clusterConfig.clusterName());

        // Whitelists are guaranteed non-null by PipelineClusterConfig record constructor (defaults to empty set)
        Set<String> allowedKafkaTopics = clusterConfig.allowedKafkaTopics();
        Set<String> allowedGrpcServices = clusterConfig.allowedGrpcServices();

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineName = pipelineEntry.getKey(); // Used for error messages
                PipelineConfig pipeline = pipelineEntry.getValue();

                if (pipeline == null || pipeline.pipelineSteps() == null) {
                    continue; // Problem with pipeline structure, handled by ReferentialIntegrityValidator
                }

                for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                    PipelineStepConfig step = stepEntry.getValue();
                    if (step == null) {
                        continue; // Problem with step structure, handled by ReferentialIntegrityValidator
                    }

                    String stepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                            step.pipelineStepId(), pipelineName, clusterConfig.clusterName());

                    // Check Kafka Listen Topics
                    if (step.kafkaListenTopics() != null) {
                        for (String topic : step.kafkaListenTopics()) {
                            if (topic == null || topic.isBlank()) {
                                errors.add(String.format("%s contains a null or blank Kafka listen topic.", stepContext));
                                continue;
                            }
                            if (!allowedKafkaTopics.contains(topic)) {
                                errors.add(String.format("%s listens to non-whitelisted Kafka topic '%s'. Allowed: %s",
                                        stepContext, topic, allowedKafkaTopics));
                            }
                        }
                    }

                    // Check Kafka Publish Topics
                    if (step.kafkaPublishTopics() != null) {
                        for (KafkaPublishTopic pubTopic : step.kafkaPublishTopics()) {
                            if (pubTopic == null || pubTopic.topic() == null || pubTopic.topic().isBlank()) {
                                errors.add(String.format("%s contains a null or blank Kafka publish topic entry.", stepContext));
                                continue;
                            }
                            if (!allowedKafkaTopics.contains(pubTopic.topic())) {
                                errors.add(String.format("%s publishes to non-whitelisted Kafka topic '%s'. Allowed: %s",
                                        stepContext, pubTopic.topic(), allowedKafkaTopics));
                            }
                        }
                    }

                    // Check gRPC Forward To Services
                    if (step.grpcForwardTo() != null) {
                        for (String service : step.grpcForwardTo()) {
                             if (service == null || service.isBlank()) {
                                errors.add(String.format("%s contains a null or blank gRPC forward-to service.", stepContext));
                                continue;
                            }
                            if (!allowedGrpcServices.contains(service)) {
                                errors.add(String.format("%s forwards to non-whitelisted gRPC service '%s'. Allowed: %s",
                                        stepContext, service, allowedGrpcServices));
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }
}