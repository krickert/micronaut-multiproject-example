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
            return errors;
        }

        LOG.debug("Performing whitelist validation for cluster: {}", clusterConfig.clusterName());

        Set<String> allowedKafkaTopics = clusterConfig.allowedKafkaTopics(); // Guaranteed non-null
        Set<String> allowedGrpcServices = clusterConfig.allowedGrpcServices(); // Guaranteed non-null

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineName = pipelineEntry.getKey();
                PipelineConfig pipeline = pipelineEntry.getValue();

                if (pipeline == null || pipeline.pipelineSteps() == null) {
                    continue;
                }

                for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                    PipelineStepConfig step = stepEntry.getValue();
                    if (step == null) {
                        continue;
                    }

                    String stepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                            step.pipelineStepId(), pipelineName, clusterConfig.clusterName());

                    TransportType transportType = step.transportType(); // Get the transport type

                    if (transportType == TransportType.KAFKA) {
                        KafkaTransportConfig kafkaConfig = step.kafkaConfig();
                        if (kafkaConfig != null) {
                            // Check Kafka Listen Topics
                            if (kafkaConfig.listenTopics() != null) { // listenTopics itself can be empty but not null due to constructor
                                for (String topic : kafkaConfig.listenTopics()) {
                                    // Element validation (null/blank) should ideally be caught by KafkaTransportConfig constructor
                                    // But defensive check here is okay too.
                                    if (topic == null || topic.isBlank()) {
                                        errors.add(String.format("%s contains a null or blank Kafka listen topic in kafkaConfig.", stepContext));
                                        continue;
                                    }
                                    if (!allowedKafkaTopics.contains(topic)) {
                                        errors.add(String.format("%s (Kafka) listens to non-whitelisted topic '%s'. Allowed: %s",
                                                stepContext, topic, allowedKafkaTopics));
                                    }
                                }
                            }

                            // Check Kafka Publish Topic Pattern
                            String publishPattern = kafkaConfig.publishTopicPattern();
                            if (publishPattern != null && !publishPattern.isBlank()) {
                                // This validation is a bit nuanced for a "pattern".
                                // If the pattern is a simple topic name, direct check is fine.
                                // If it's a true pattern like "${pipelineId}.${stepId}.out",
                                // you'd either need to:
                                // 1. Ensure such patterns are implicitly allowed.
                                // 2. Resolve the pattern to a concrete topic name for this step and check that.
                                // 3. Have a list of allowed patterns.
                                // For now, let's assume if it's a plain string, it should be in the whitelist
                                // if it's meant to be a resolvable, publishable topic.
                                // This check might be too simplistic if patterns are complex.
                                // A simple interpretation: if a step *publishes*, its output topic must be known and allowed.
                                // Let's assume for now the pattern itself, if non-blank, should map to an allowed topic.
                                // This means publishTopicPattern often might just be a direct topic name.
                                if (!isPatternLikelyAllowed(publishPattern, allowedKafkaTopics, step, pipelineName, clusterConfig.clusterName())) {
                                    errors.add(String.format("%s (Kafka) uses a publishTopicPattern '%s' that does not resolve to/match an allowed topic. Allowed: %s",
                                            stepContext, publishPattern, allowedKafkaTopics));
                                }
                            }
                        } else if (step.transportType() == TransportType.KAFKA) { // Should not happen if constructor validation is good
                            errors.add(String.format("%s is KAFKA type but has null kafkaConfig.", stepContext));
                        }
                    } else if (transportType == TransportType.GRPC) {
                        GrpcTransportConfig grpcConfig = step.grpcConfig();
                        if (grpcConfig != null) {
                            String serviceId = grpcConfig.serviceId(); // serviceId is non-null/blank by its own record constructor
                            if (!allowedGrpcServices.contains(serviceId)) {
                                errors.add(String.format("%s (gRPC) uses non-whitelisted serviceId '%s'. Allowed: %s",
                                        stepContext, serviceId, allowedGrpcServices));
                            }
                        } else if (step.transportType() == TransportType.GRPC) { // Should not happen
                            errors.add(String.format("%s is GRPC type but has null grpcConfig.", stepContext));
                        }
                    }
                    // No specific whitelist validation for INTERNAL transport type
                }
            }
        }
        return errors;
    }

    /**
     * Helper method to determine if a publish pattern is allowed.
     * This is a placeholder for potentially more complex logic.
     * For now, it checks if the pattern (if it's a simple topic name) is directly in the allowed list.
     * Or if it's a variable pattern, it might try to resolve it and check.
     */
    private boolean isPatternLikelyAllowed(String pattern, Set<String> allowedKafkaTopics,
                                           PipelineStepConfig step, String pipelineName, String clusterName) {
        if (pattern == null || pattern.isBlank()) {
            return true; // No pattern to check
        }
        // Simplistic check: if the pattern itself is an allowed topic
        if (allowedKafkaTopics.contains(pattern)) {
            return true;
        }
        // More advanced: try to resolve known variables if any are used
        // Example: "${pipelineId}.${stepId}.output"
        String resolvedTopic = pattern.replace("${clusterId}", clusterName) // if you had clusterId
                .replace("${pipelineId}", pipelineName)
                .replace("${stepId}", step.pipelineStepId());
        // If after resolution it's different from original pattern and is in allowed list
        if (!resolvedTopic.equals(pattern) && allowedKafkaTopics.contains(resolvedTopic)) {
            return true;
        }

        // If it looks like a pattern with unresolved variables, it's harder to validate directly
        // against a list of explicit topic names without a clear convention or an allowed patterns list.
        // For now, if it's not directly in the list and doesn't simply resolve to one, assume it's problematic
        // unless specific pattern-matching rules are in place for whitelisting.
        // This indicates a potential gap if you use complex patterns that aren't explicitly enumerated
        // or covered by a whitelisted pattern rule.
        if (resolvedTopic.contains("${")) { // Still contains unresolved placeholders
            LOG.warn("Publish pattern '{}' for step '{}' contains unresolved variables and is not directly whitelisted.", pattern, step.pipelineStepId());
            // Depending on policy, this could be an error or a warning.
            // For strict whitelisting of final topic names, this would be an error if not directly matched.
            return false; // Defaulting to false if it's a pattern that doesn't resolve to a whitelisted topic
        }

        // If it resolved to something but that something isn't in the list
        return allowedKafkaTopics.contains(resolvedTopic);
    }
}