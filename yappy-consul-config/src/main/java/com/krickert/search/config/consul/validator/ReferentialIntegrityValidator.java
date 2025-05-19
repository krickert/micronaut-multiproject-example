package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

@Singleton
public class ReferentialIntegrityValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(ReferentialIntegrityValidator.class);

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();

        if (clusterConfig == null) {
            errors.add("PipelineClusterConfig is null.");
            return errors;
        }
        final String currentClusterName = clusterConfig.clusterName();
        LOG.debug("Performing referential integrity checks for cluster: {}", currentClusterName);

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                        clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (clusterConfig.pipelineGraphConfig() == null) {
            LOG.debug("PipelineGraphConfig is null in cluster: {}. No pipelines to validate further.", currentClusterName);
            return errors;
        }
        if (clusterConfig.pipelineGraphConfig().pipelines() == null) {
            errors.add(String.format("PipelineGraphConfig.pipelines map is null in cluster '%s'.", currentClusterName));
            return errors; // Cannot proceed.
        }

        Set<String> declaredPipelineNames = new HashSet<>();
        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineKey = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();
            String pipelineContextForKey = String.format("Pipeline with map key '%s' in cluster '%s'", pipelineKey, currentClusterName);

            if (pipelineKey == null || pipelineKey.isBlank()) {
                errors.add(String.format("Pipeline map in cluster '%s' contains a null or blank key.", currentClusterName));
                continue;
            }
            if (pipeline == null) {
                errors.add(String.format("%s: definition is null.", pipelineContextForKey));
                continue;
            }
            if (pipeline.name() == null || pipeline.name().isBlank()) { // Ensure pipeline name itself is valid
                errors.add(String.format("%s: pipeline name field is null or blank.", pipelineContextForKey));
                // continue; // Don't continue if we want to check key mismatch even with bad name
            }

            if (pipeline.name() != null && !pipelineKey.equals(pipeline.name())) {
                errors.add(String.format("%s: map key '%s' does not match its name field '%s'.",
                        pipelineContextForKey, pipelineKey, pipeline.name()));
            }
            if (pipeline.name() != null && !pipeline.name().isBlank() && !declaredPipelineNames.add(pipeline.name())) {
                errors.add(String.format("Duplicate pipeline name '%s' found in cluster '%s'. Pipeline names must be unique.",
                        pipeline.name(), currentClusterName));
            }

            if (pipeline.pipelineSteps() == null) {
                errors.add(String.format("Pipeline '%s' (cluster '%s') has a null pipelineSteps map.",
                        (pipeline.name() != null ? pipeline.name() : pipelineKey), currentClusterName));
                continue;
            }

            Set<String> declaredStepNamesInPipeline = new HashSet<>();
            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                String stepKey = stepEntry.getKey();
                PipelineStepConfig step = stepEntry.getValue();
                String stepContextForKey = String.format("Step with map key '%s' in pipeline '%s' (cluster '%s')",
                        stepKey, (pipeline.name() != null ? pipeline.name() : pipelineKey), currentClusterName);

                if (stepKey == null || stepKey.isBlank()) {
                    errors.add(String.format("Pipeline '%s' (cluster '%s') contains a step with a null or blank map key.",
                            (pipeline.name() != null ? pipeline.name() : pipelineKey), currentClusterName));
                    continue;
                }
                if (step == null) {
                    errors.add(String.format("%s: definition is null.", stepContextForKey));
                    continue;
                }
                if (step.stepName() == null || step.stepName().isBlank()) {
                    errors.add(String.format("%s: stepName field is null or blank.", stepContextForKey));
                    // continue; // Don't continue if we want to check key mismatch
                }

                String currentStepNameForContext = (step.stepName() != null && !step.stepName().isBlank()) ? step.stepName() : stepKey;
                String currentStepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                        currentStepNameForContext, (pipeline.name() != null ? pipeline.name() : pipelineKey), currentClusterName);


                if (step.stepName() != null && !stepKey.equals(step.stepName())) {
                    errors.add(String.format("%s: map key '%s' does not match its stepName field '%s'.",
                            stepContextForKey, stepKey, step.stepName()));
                }
                if (step.stepName() != null && !step.stepName().isBlank() && !declaredStepNamesInPipeline.add(step.stepName())) {
                    errors.add(String.format("Duplicate stepName '%s' found in pipeline '%s' (cluster '%s').",
                            step.stepName(), (pipeline.name() != null ? pipeline.name() : pipelineKey), currentClusterName));
                }

                // Check processorInfo and its link to availableModules
                String implementationKey = null;
                if (step.processorInfo() != null) { // processorInfo is @NotNull
                    if (step.processorInfo().grpcServiceName() != null && !step.processorInfo().grpcServiceName().isBlank()) {
                        implementationKey = step.processorInfo().grpcServiceName();
                    } else if (step.processorInfo().internalProcessorBeanName() != null && !step.processorInfo().internalProcessorBeanName().isBlank()) {
                        implementationKey = step.processorInfo().internalProcessorBeanName();
                    } else {
                        // This case should be prevented by ProcessorInfo constructor validation
                        errors.add(String.format("%s: processorInfo must have either grpcServiceName or internalProcessorBeanName correctly set.", currentStepContext));
                    }
                } else {
                    // This should be prevented by PipelineStepConfig constructor making processorInfo @NotNull
                    errors.add(String.format("%s: processorInfo is null, which should be prevented by model constraints.", currentStepContext));
                }

                if (implementationKey != null) {
                    if (!availableModules.containsKey(implementationKey)) {
                        errors.add(String.format("%s references unknown implementationKey '%s' (from processorInfo). Available module implementationIds: %s",
                                currentStepContext, implementationKey, availableModules.keySet()));
                    } else {
                        PipelineModuleConfiguration module = availableModules.get(implementationKey);
                        // Only report errors for non-empty customConfig (with actual content)
                        boolean hasNonEmptyJsonConfig = step.customConfig() != null &&
                                step.customConfig().jsonConfig() != null &&
                                !step.customConfig().jsonConfig().isEmpty();
                        boolean hasNonEmptyConfigParams = step.customConfig() != null &&
                                step.customConfig().configParams() != null &&
                                !step.customConfig().configParams().isEmpty();

                        if (module != null &&
                                (hasNonEmptyJsonConfig || hasNonEmptyConfigParams) &&
                                module.customConfigSchemaReference() == null &&
                                (step.customConfigSchemaId() == null || step.customConfigSchemaId().isBlank())) {
                            errors.add(String.format("%s has non-empty customConfig but its module '%s' does not define a customConfigSchemaReference, and step does not define customConfigSchemaId.",
                                    currentStepContext, module.implementationId()));
                        }
                        if (step.customConfigSchemaId() != null && !step.customConfigSchemaId().isBlank() && module != null && module.customConfigSchemaReference() != null) {
                            // Using toIdentifier() as fixed previously
                            if (!step.customConfigSchemaId().equals(module.customConfigSchemaReference().toIdentifier())) {
                                LOG.warn("{}: step's customConfigSchemaId ('{}') differs from module's schema reference identifier ('{}'). Assuming step's ID is an override or specific reference.",
                                        currentStepContext, step.customConfigSchemaId(), module.customConfigSchemaReference().toIdentifier());
                            }
                        }
                    }
                }

                // Validate KafkaInputDefinition if present
                if (step.kafkaInputs() != null) {
                    for (int i = 0; i < step.kafkaInputs().size(); i++) {
                        KafkaInputDefinition inputDef = step.kafkaInputs().get(i);
                        String inputContext = String.format("%s, kafkaInput #%d", currentStepContext, i + 1);
                        if (inputDef == null) {
                            errors.add(String.format("%s: definition is null.", inputContext));
                            continue;
                        }
                        // listenTopics @NotEmpty and elements non-blank is handled by KafkaInputDefinition constructor
                        // consumerGroupId is optional
                        if (inputDef.kafkaConsumerProperties() != null) {
                            for (Map.Entry<String, String> propEntry : inputDef.kafkaConsumerProperties().entrySet()) {
                                if (propEntry.getKey() == null || propEntry.getKey().isBlank()) {
                                    errors.add(String.format("%s kafkaConsumerProperties contains a null or blank key.", inputContext));
                                }
                                // Null values for Kafka properties might be acceptable.
                            }
                        }
                    }
                }


                // Validate transport properties within each OutputTarget
                if (step.outputs() != null) {
                    for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : step.outputs().entrySet()) {
                        String outputKey = outputEntry.getKey();
                        PipelineStepConfig.OutputTarget outputTarget = outputEntry.getValue();
                        String outputContext = String.format("%s, output '%s'", currentStepContext, outputKey);

                        if (outputTarget == null) {
                            errors.add(String.format("%s: definition is null.", outputContext));
                            continue;
                        }

                        if (outputTarget.transportType() == TransportType.KAFKA && outputTarget.kafkaTransport() != null) {
                            KafkaTransportConfig kafkaConfig = outputTarget.kafkaTransport();
                            if (kafkaConfig.kafkaProducerProperties() != null) {
                                for (Map.Entry<String, String> propEntry : kafkaConfig.kafkaProducerProperties().entrySet()) {
                                    if (propEntry.getKey() == null || propEntry.getKey().isBlank()) {
                                        errors.add(String.format("%s kafkaTransport.kafkaProducerProperties contains a null or blank key.", outputContext));
                                    }
                                }
                            }
                        } else if (outputTarget.transportType() == TransportType.GRPC && outputTarget.grpcTransport() != null) {
                            GrpcTransportConfig grpcConfig = outputTarget.grpcTransport();
                            if (grpcConfig.grpcClientProperties() != null) {
                                for (Map.Entry<String, String> propEntry : grpcConfig.grpcClientProperties().entrySet()) {
                                    if (propEntry.getKey() == null || propEntry.getKey().isBlank()) {
                                        errors.add(String.format("%s grpcTransport.grpcClientProperties contains a null or blank key.", outputContext));
                                    }
                                }
                            }
                        }
                    }
                }

                // Validate that targetStepName in outputs refer to existing step names within the current pipeline
                if (step.outputs() != null) {
                    Set<String> currentPipelineStepNames = pipeline.pipelineSteps().keySet();
                    validateOutputTargetReferences(errors, step.outputs(), currentPipelineStepNames, currentStepContext, (pipeline.name() != null ? pipeline.name() : pipelineKey));
                }
            } // End of step iteration
        } // End of pipeline iteration
        return errors;
    }

    private void validateOutputTargetReferences(List<String> errors,
                                                Map<String, PipelineStepConfig.OutputTarget> outputs,
                                                Set<String> existingStepNamesInPipeline,
                                                String sourceStepContext, String pipelineName) {
        if (outputs == null) { // Should be handled by caller, but defensive.
            return;
        }
        for (Map.Entry<String, PipelineStepConfig.OutputTarget> outputEntry : outputs.entrySet()) {
            String outputKey = outputEntry.getKey();
            PipelineStepConfig.OutputTarget outputTarget = outputEntry.getValue();

            if (outputTarget == null) {
                errors.add(String.format("%s: OutputTarget for key '%s' is null.", sourceStepContext, outputKey));
                continue;
            }

            // targetStepName non-null/blank is handled by OutputTarget constructor
            if (outputTarget.targetStepName() != null && !outputTarget.targetStepName().isBlank()) { // Check again for safety
                // Check if this is a cross-pipeline reference (contains a dot)
                if (outputTarget.targetStepName().contains(".")) {
                    // This is a cross-pipeline reference, format is expected to be "pipelineName.stepName"
                    // We don't validate cross-pipeline references here as they might be valid
                    // The actual validation of cross-pipeline references should be done elsewhere
                    LOG.debug("{}: output '{}' contains a cross-pipeline reference to '{}'. Skipping validation in ReferentialIntegrityValidator.",
                            sourceStepContext, outputKey, outputTarget.targetStepName());
                } else if (!existingStepNamesInPipeline.contains(outputTarget.targetStepName())) {
                    errors.add(String.format("%s: output '%s' contains reference to unknown targetStepName '%s' in pipeline '%s'. Available step names: %s",
                            sourceStepContext, outputKey, outputTarget.targetStepName(), pipelineName, existingStepNamesInPipeline));
                }
            } else {
                // This case should be caught by OutputTarget's constructor if targetStepName is @NotBlank
                errors.add(String.format("%s: output '%s' has a null or blank targetStepName (should be caught by model validation).", sourceStepContext, outputKey));
            }
        }
    }
}
