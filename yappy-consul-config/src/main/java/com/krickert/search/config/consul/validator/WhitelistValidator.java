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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Singleton
public class WhitelistValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(WhitelistValidator.class);

    // Regex to capture conventional topic names.
    // It expects pipelineName and stepName not to contain dots.
    // It also expects clusterName not to contain dots.
    // Example conventional topic: yappy.pipeline.my-pipeline-name.step.my-step-name.output
    private static final Pattern KAFKA_TOPIC_CONVENTION_PATTERN = Pattern.compile(
            "yappy\\.pipeline\\.([^.]+)\\.step\\.([^.]+)\\.(input|output|error|dead-letter)" // Added more common suffixes
    );
    // Variables for placeholder replacement. Using convention that variables are ${variableName}
    // We will replace these with actual values before checking against the convention pattern if needed,
    // or ensure the topic string in config *is* the fully resolved name.
    // The current isKafkaTopicPermitted resolves them first.

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();

        if (clusterConfig == null) {
            LOG.warn("PipelineClusterConfig is null, skipping whitelist validation.");
            return errors;
        }

        LOG.debug("Performing whitelist validation for cluster: {}", clusterConfig.clusterName());

        final String currentClusterName = clusterConfig.clusterName(); // Assuming clusterName cannot have dots
        Set<String> allowedKafkaTopics = clusterConfig.allowedKafkaTopics();
        Set<String> allowedGrpcServices = clusterConfig.allowedGrpcServices();

        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineName = pipelineEntry.getKey(); // Assuming pipelineName cannot have dots
                PipelineConfig pipeline = pipelineEntry.getValue();

                if (pipeline == null || !pipelineName.equals(pipeline.name())) {
                    // Basic integrity check, though ReferentialIntegrityValidator might also cover this.
                    if (pipeline != null) {
                        errors.add(String.format("Pipeline key '%s' does not match pipeline name '%s' in cluster '%s'.",
                                pipelineEntry.getKey(), pipeline.name(), currentClusterName));
                    } else {
                        errors.add(String.format("Pipeline definition for key '%s' is null in cluster '%s'.",
                                pipelineEntry.getKey(), currentClusterName));
                    }
                    continue;
                }


                if (pipeline.pipelineSteps() == null) {
                    continue;
                }

                for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                    PipelineStepConfig step = stepEntry.getValue();
                    if (step == null) {
                        continue;
                    }
                    if (!stepEntry.getKey().equals(step.stepName())) {
                        errors.add(String.format("Step key '%s' does not match step name '%s' in pipeline '%s', cluster '%s'.",
                                stepEntry.getKey(), step.stepName(), pipelineName, currentClusterName));
                        continue;
                    }


                    // Assuming stepName cannot have dots
                    String stepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                            step.stepName(), pipelineName, currentClusterName);

                    if (step.outputs() != null) {
                        for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : step.outputs().entrySet()) {
                            String outputKey = outputEntry.getKey();
                            PipelineStepConfig.OutputTarget outputTarget = outputEntry.getValue();

                            if (outputTarget == null) {
                                errors.add(String.format("%s has a null OutputTarget for output key '%s'.", stepContext, outputKey));
                                continue;
                            }

                            String outputContext = String.format("%s, output '%s'", stepContext, outputKey);
                            TransportType transportType = outputTarget.transportType();

                            if (transportType == TransportType.KAFKA) {
                                KafkaTransportConfig kafkaConfig = outputTarget.kafkaTransport();
                                if (kafkaConfig != null) {
                                    String publishTopic = kafkaConfig.topic();
                                    if (publishTopic != null && !publishTopic.isBlank()) {
                                        if (!isKafkaTopicPermitted(publishTopic, allowedKafkaTopics,
                                                currentClusterName, // Pass cluster name explicitly
                                                pipelineName,       // Pass pipeline name explicitly
                                                step.stepName())) { // Pass step name explicitly
                                            errors.add(String.format("%s (Kafka) publishes to topic '%s' which is not whitelisted by convention or explicit list. Allowed explicit: %s",
                                                    outputContext, publishTopic, allowedKafkaTopics));
                                        }
                                    }
                                    // As discussed, listen topics are not handled here based on current OutputTarget model.
                                } else {
                                    errors.add(String.format("%s is KAFKA type but has null kafkaTransport.", outputContext));
                                }
                            } else if (transportType == TransportType.GRPC) {
                                GrpcTransportConfig grpcConfig = outputTarget.grpcTransport();
                                if (grpcConfig != null) {
                                    String serviceName = grpcConfig.serviceName();
                                    if (!allowedGrpcServices.contains(serviceName)) {
                                        errors.add(String.format("%s (gRPC) uses non-whitelisted serviceName '%s'. Allowed: %s",
                                                outputContext, serviceName, allowedGrpcServices));
                                    }
                                } else {
                                    errors.add(String.format("%s is GRPC type but has null grpcTransport.", outputContext));
                                }
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }

    /**
     * Checks if the resolved topic name matches the defined Kafka topic naming convention.
     * This version assumes pipelineName and stepName do not contain dots.
     *
     * @param resolvedTopicName The fully resolved topic name (no variables like ${...}).
     * @param expectedPipelineName The name of the pipeline this topic should belong to.
     * @param expectedStepName The name of the step this topic should belong to.
     * @return true if the topic matches the convention, false otherwise.
     */
    private boolean topicMatchesNamingConvention(String resolvedTopicName, String expectedPipelineName, String expectedStepName) {
        if (resolvedTopicName == null || expectedPipelineName == null || expectedStepName == null) {
            return false;
        }

        Matcher matcher = KAFKA_TOPIC_CONVENTION_PATTERN.matcher(resolvedTopicName);
        if (matcher.matches()) {
            String actualPipelineName = matcher.group(1);
            String actualStepName = matcher.group(2);
            // String topicType = matcher.group(3); // e.g., input, output, error

            // Check if the extracted pipeline and step names match the expected ones.
            // This ensures the topic belongs to the correct pipeline and step context.
            boolean matchesContext = expectedPipelineName.equals(actualPipelineName) &&
                    expectedStepName.equals(actualStepName);
            if (!matchesContext) {
                LOG.warn("Topic '{}' matches convention pattern but has mismatched context. Expected pipeline: '{}', step: '{}'. Got pipeline: '{}', step: '{}'.",
                        resolvedTopicName, expectedPipelineName, expectedStepName, actualPipelineName, actualStepName);
            }
            return matchesContext;
        }
        return false;
    }


    /**
     * Determines if a Kafka topic is permitted, either by explicit whitelist or by conforming to a naming convention.
     *
     * @param topicNameInConfig The topic name as defined in the configuration (may contain variables).
     * @param allowedKafkaTopics Set of explicitly whitelisted topic names.
     * @param currentClusterName The actual name of the current cluster.
     * @param currentPipelineName The actual name of the current pipeline.
     * @param currentStepName The actual name of the current step.
     * @return true if the topic is permitted, false otherwise.
     */
    private boolean isKafkaTopicPermitted(String topicNameInConfig, Set<String> allowedKafkaTopics,
                                          String currentClusterName, String currentPipelineName, String currentStepName) {
        if (topicNameInConfig == null || topicNameInConfig.isBlank()) {
            // Consider if blank topic in config is an error.
            // For whitelisting, a blank topic isn't usually "permitted" unless explicitly in the list.
            return allowedKafkaTopics.contains(""); // Unlikely, but covers the case.
        }

        // 1. Resolve variables in the topic name from config
        //    The convention itself uses <pipelineName> and <stepName> as part of the dot-separated structure,
        //    not as ${variables} within the segments.
        //    So, topicNameInConfig should ideally be the fully formed name or use variables
        //    that resolve to a name matching the convention.
        //    The variables ${clusterName}, ${pipelineName}, ${stepName} are for allowing topic strings
        //    in config to be like "yappy.pipeline.${pipelineName}.step.${stepName}.output"
        //    which then resolves to "yappy.pipeline.my-pipe.step.my-step.output".
        //    This resolved string is then checked against the KAFKA_TOPIC_CONVENTION_PATTERN.

        String resolvedTopic = topicNameInConfig
                .replace("${clusterName}", currentClusterName) // This variable is not in our current convention string directly
                .replace("${pipelineName}", currentPipelineName)
                .replace("${stepName}", currentStepName);
        // Add other potential variables if used, e.g. .replace("${outputKey}", outputKeyFromStepOutput);

        // 2. Check if the original or resolved topic is in the explicit whitelist first.
        //    This allows overriding or listing topics that don't fit the convention.
        if (allowedKafkaTopics.contains(topicNameInConfig)) {
            LOG.debug("Topic '{}' found in explicit whitelist.", topicNameInConfig);
            return true;
        }
        if (!resolvedTopic.equals(topicNameInConfig) && allowedKafkaTopics.contains(resolvedTopic)) {
            LOG.debug("Topic '{}' (resolved to '{}') found in explicit whitelist.", topicNameInConfig, resolvedTopic);
            return true;
        }

        // 3. If not explicitly whitelisted, check if the resolved topic matches the naming convention.
        //    Only check convention if the topic is fully resolved (no more template variables).
        if (!resolvedTopic.contains("${")) {
            // We pass currentPipelineName and currentStepName to ensure the conventional topic
            // actually matches the context it's being used in.
            if (topicMatchesNamingConvention(resolvedTopic, currentPipelineName, currentStepName)) {
                LOG.debug("Topic '{}' (resolved to '{}') matches naming convention for pipeline '{}' and step '{}'.",
                        topicNameInConfig, resolvedTopic, currentPipelineName, currentStepName);
                return true;
            }
        } else {
            // Topic still contains unresolved ${...} variables after attempting resolution.
            LOG.warn("Topic '{}' from config for pipeline '{}', step '{}' still contains unresolved variables ('{}') after attempted resolution. " +
                            "Cannot check against naming convention and not found in explicit whitelist.",
                    topicNameInConfig, currentPipelineName, currentStepName, resolvedTopic);
            return false;
        }

        // 4. If not in explicit list and not matching convention (after attempting resolution).
        LOG.debug("Topic '{}' (resolved to '{}') for pipeline '{}', step '{}' is not explicitly whitelisted and does not match naming convention.",
                topicNameInConfig, resolvedTopic, currentPipelineName, currentStepName);
        return false;
    }
}