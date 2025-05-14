package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Validates the referential integrity of a PipelineClusterConfig.
 * <br/>
 * This validator ensures that:
 * 1. Basic structure of PipelineClusterConfig and its main components are sound (null checks, name presence).
 * 2. Pipeline step implementation IDs reference valid modules in the available modules map.
 * 3. Step IDs are unique within each pipeline and match their map keys.
 * 4. Pipeline names are unique within the graph and match their map keys.
 * 5. For Kafka/gRPC transport types, the respective configuration objects (kafkaConfig/grpcConfig) are present.
 * (Note: The record constructors for PipelineStepConfig, KafkaTransportConfig, GrpcTransportConfig
 * already handle many low-level null/blank checks for their direct fields.)
 * 6. Referenced 'nextSteps' and 'errorSteps' exist as valid step IDs within the same pipeline.
 * <br/>
 * The validator is designed to collect all errors rather than failing fast on the first error.
 */
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
        // clusterConfig.clusterName() is validated by its record constructor

        LOG.debug("Performing referential integrity checks for cluster: {}", clusterConfig.clusterName());

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                        clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (clusterConfig.pipelineGraphConfig() == null) {
            LOG.debug("PipelineGraphConfig is null in cluster: {}. No pipelines to validate further.", clusterConfig.clusterName());
            // If there are defined modules or allowed topics/services, but no pipelines, it might be an anomaly
            // but not strictly a referential integrity issue of the (non-existent) pipelines themselves.
            return errors; // No pipelines to check steps for.
        }
        if (clusterConfig.pipelineGraphConfig().pipelines() == null) {
            // This should be prevented by PipelineGraphConfig constructor (defaults to empty map)
            errors.add(String.format("PipelineGraphConfig.pipelines map is null in cluster '%s'.", clusterConfig.clusterName()));
            return errors; // Cannot proceed.
        }

        // Check for pipeline name uniqueness and consistency
        Set<String> pipelineKeysInGraph = clusterConfig.pipelineGraphConfig().pipelines().keySet();
        Set<String> declaredPipelineNames = new HashSet<>();

        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineKey = pipelineEntry.getKey(); // Key from the map
            PipelineConfig pipeline = pipelineEntry.getValue();

            String pipelineContextForKey = String.format("Pipeline with map key '%s' in cluster '%s'", pipelineKey, clusterConfig.clusterName());

            if (pipelineKey == null || pipelineKey.isBlank()) {
                errors.add(String.format("Pipeline map in cluster '%s' contains a null or blank key.", clusterConfig.clusterName()));
                continue; // Cannot reliably process this entry.
            }

            if (pipeline == null) {
                errors.add(String.format("%s: definition is null.", pipelineContextForKey));
                continue;
            }
            // pipeline.name() is validated by its record constructor not to be null/blank.
            if (!pipelineKey.equals(pipeline.name())) {
                errors.add(String.format("%s: map key '%s' does not match its name field '%s'.",
                        pipelineContextForKey, pipelineKey, pipeline.name()));
            }
            if (!declaredPipelineNames.add(pipeline.name())) {
                errors.add(String.format("Duplicate pipeline name '%s' found in cluster '%s'. Pipeline names must be unique.",
                        pipeline.name(), clusterConfig.clusterName()));
            }

            // Proceed with step validation
            if (pipeline.pipelineSteps() == null) {
                // This should be prevented by PipelineConfig constructor (defaults to empty map)
                errors.add(String.format("Pipeline '%s' (cluster '%s') has a null pipelineSteps map.", pipeline.name(), clusterConfig.clusterName()));
                continue;
            }

            Set<String> stepKeysInPipeline = pipeline.pipelineSteps().keySet();
            Set<String> declaredStepIdsInPipeline = new HashSet<>();

            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                String stepKey = stepEntry.getKey(); // Key from the map
                PipelineStepConfig step = stepEntry.getValue();
                String stepContextForKey = String.format("Step with map key '%s' in pipeline '%s' (cluster '%s')",
                        stepKey, pipeline.name(), clusterConfig.clusterName());

                if (stepKey == null || stepKey.isBlank()) {
                    errors.add(String.format("Pipeline '%s' (cluster '%s') contains a step with a null or blank map key.",
                            pipeline.name(), clusterConfig.clusterName()));
                    continue; // Cannot reliably process.
                }

                if (step == null) {
                    errors.add(String.format("%s: definition is null.", stepContextForKey));
                    continue;
                }

                // step.pipelineStepId() is validated by its record constructor not to be null/blank.
                // Use actual stepId for context from here on.
                String currentStepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                        step.pipelineStepId(), pipeline.name(), clusterConfig.clusterName());

                if (!stepKey.equals(step.pipelineStepId())) {
                    errors.add(String.format("%s: map key '%s' does not match its pipelineStepId field '%s'.",
                            stepContextForKey, stepKey, step.pipelineStepId()));
                }
                if (!declaredStepIdsInPipeline.add(step.pipelineStepId())) {
                    errors.add(String.format("Duplicate step ID '%s' found in pipeline '%s' (cluster '%s').",
                            step.pipelineStepId(), pipeline.name(), clusterConfig.clusterName()));
                }

                // Check pipelineImplementationId
                // (step.pipelineImplementationId() validated by its record constructor not null/blank)
                if (!availableModules.containsKey(step.pipelineImplementationId())) {
                    errors.add(String.format("%s references unknown pipelineImplementationId '%s'. Available modules: %s",
                            currentStepContext, step.pipelineImplementationId(), availableModules.keySet()));
                } else {
                    // SchemaReference in PipelineModuleConfiguration is validated by its own record constructor.
                    PipelineModuleConfiguration module = availableModules.get(step.pipelineImplementationId());
                    if (module != null && step.customConfig() != null && (step.customConfig().jsonConfig() != null && !step.customConfig().jsonConfig().equals(JsonConfigOptions.DEFAULT_EMPTY_JSON)) && module.customConfigSchemaReference() == null) {
                        errors.add(String.format("%s has customConfig but its module '%s' does not define a customConfigSchemaReference.",
                                currentStepContext, module.implementationId()));
                    }
                }

                // Check transport type specific configurations
                // The PipelineStepConfig constructor already validates that if transportType is KAFKA,
                // kafkaConfig is non-null, and grpcConfig is null (and vice-versa for GRPC).
                // So, we mainly check elements within those configs if they exist.

                if (step.transportType() == TransportType.KAFKA) {
                    KafkaTransportConfig kafkaConfig = step.kafkaConfig(); // Should be non-null
                    if (kafkaConfig != null) { // Defensive check
                        // listenTopics elements & publishTopicPattern null/blank are handled by KafkaTransportConfig constructor
                        // kafkaProperties keys/values null/blank checks
                        if (kafkaConfig.kafkaProperties() != null) {
                            for(Map.Entry<String, String> propEntry : kafkaConfig.kafkaProperties().entrySet()){
                                if(propEntry.getKey() == null || propEntry.getKey().isBlank()){
                                    errors.add(String.format("%s kafkaConfig.kafkaProperties contains a null or blank key.", currentStepContext));
                                }
                                if(propEntry.getValue() == null){
                                    errors.add(String.format("%s kafkaConfig.kafkaProperties contains a null value for key '%s'.", currentStepContext, propEntry.getKey()));
                                }
                            }
                        }
                    }
                } else if (step.transportType() == TransportType.GRPC) {
                    GrpcTransportConfig grpcConfig = step.grpcConfig(); // Should be non-null
                    if (grpcConfig != null) { // Defensive check
                        // serviceId non-null/blank is handled by GrpcTransportConfig constructor
                        // grpcProperties keys/values null/blank checks
                        if (grpcConfig.grpcProperties() != null) {
                            for(Map.Entry<String, String> propEntry : grpcConfig.grpcProperties().entrySet()){
                                if(propEntry.getKey() == null || propEntry.getKey().isBlank()){
                                    errors.add(String.format("%s grpcConfig.grpcProperties contains a null or blank key.", currentStepContext));
                                }
                                if(propEntry.getValue() == null){
                                    errors.add(String.format("%s grpcConfig.grpcProperties contains a null value for key '%s'.", currentStepContext, propEntry.getKey()));
                                }
                            }
                        }
                    }
                }

                // Validate that nextSteps and errorSteps refer to existing step IDs within the current pipeline
                Set<String> currentPipelineStepIds = pipeline.pipelineSteps().keySet(); // More direct than declaredStepIdsInPipeline for lookup
                validateStepReferences(errors, step.nextSteps(), currentPipelineStepIds, "nextSteps", currentStepContext, pipeline.name());
                validateStepReferences(errors, step.errorSteps(), currentPipelineStepIds, "errorSteps", currentStepContext, pipeline.name());

            } // End of step iteration
        } // End of pipeline iteration
        return errors;
    }


    private void validateStepReferences(List<String> errors, List<String> referencedStepIds,
                                        Set<String> existingStepKeysInPipeline, // Changed to Set<String> for direct key lookup
                                        String referenceType, String sourceStepContext, String pipelineName) {
        // referencedStepIds list itself is guaranteed non-null and its elements non-null/blank
        // by PipelineStepConfig's constructor. This method checks for existence.
        for (String referencedStepId : referencedStepIds) {
            if (!existingStepKeysInPipeline.contains(referencedStepId)) {
                errors.add(String.format("%s: %s contains reference to unknown step ID '%s' in pipeline '%s'. Available step IDs: %s",
                        sourceStepContext, referenceType, referencedStepId, pipelineName, existingStepKeysInPipeline));
            }
        }
    }
}