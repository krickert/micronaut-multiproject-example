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
 * 1. The PipelineClusterConfig and its main components are not null
 * 2. Pipeline step implementation IDs reference valid modules in the available modules map
 * 3. Step IDs are unique within each pipeline
 * 4. Pipeline names are unique within the graph
 * <br/>
 * The validator is designed to collect all errors rather than failing fast on the first error.
 */

@Singleton // Each rule is a bean
public class ReferentialIntegrityValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(ReferentialIntegrityValidator.class);

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();
        // Ensure clusterConfig and its main components are not null before accessing them.
        // Record constructors should prevent nulls for non-optional fields, but the overall objects can be null.
        if (clusterConfig == null) {
            // This case should ideally be caught by DefaultConfigurationValidator before calling rules,
            // but defensive checks in rules are okay too.
            errors.add("PipelineClusterConfig is null.");
            return errors;
        }
        if (clusterConfig.clusterName() == null || clusterConfig.clusterName().isBlank()){
            errors.add("PipelineClusterConfig clusterName is null or blank.");
            // No return here, collect all errors.
        }

        LOG.debug("Performing referential integrity checks for cluster: {}", clusterConfig.clusterName());

        Map<String, PipelineModuleConfiguration> availableModules =
                (clusterConfig.pipelineModuleMap() != null && clusterConfig.pipelineModuleMap().availableModules() != null) ?
                        clusterConfig.pipelineModuleMap().availableModules() : Collections.emptyMap();

        if (availableModules.isEmpty() && clusterConfig.pipelineGraphConfig() != null &&
                clusterConfig.pipelineGraphConfig().pipelines() != null && !clusterConfig.pipelineGraphConfig().pipelines().isEmpty()) {
            // If there are pipelines defined but no modules, any step will be an error.
            // This can be a specific error or let the per-step check catch it.
            LOG.warn("Cluster '{}' has pipelines defined but no available modules in PipelineModuleMap.", clusterConfig.clusterName());
        }


        if (clusterConfig.pipelineGraphConfig() != null && clusterConfig.pipelineGraphConfig().pipelines() != null) {
            // Check for pipeline name uniqueness within the graph
            Set<String> pipelineNames = new HashSet<>();

            for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineName = pipelineEntry.getKey();
                PipelineConfig pipeline = pipelineEntry.getValue();

                if (pipeline == null) {
                    errors.add(String.format("Pipeline definition for key '%s' is null in cluster '%s'.", pipelineName, clusterConfig.clusterName()));
                    continue;
                }
                if (pipeline.name() == null || pipeline.name().isBlank()){
                    errors.add(String.format("Pipeline with key '%s' has a null or blank name in cluster '%s'.", pipelineName, clusterConfig.clusterName()));
                } else {
                    // Check for pipeline name uniqueness
                    if (!pipelineNames.add(pipeline.name())) {
                        errors.add(String.format("Duplicate pipeline name '%s' found in cluster '%s'. Pipeline names must be unique within a graph.", 
                                pipeline.name(), clusterConfig.clusterName()));
                    }
                }

                if (pipeline.pipelineSteps() != null) {
                    // Check for step ID uniqueness within the pipeline
                    Set<String> stepIds = new HashSet<>();

                    for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                        String stepIdInMap = stepEntry.getKey();
                        PipelineStepConfig step = stepEntry.getValue();

                        if (step == null) {
                            errors.add(String.format("Pipeline step definition for key '%s' in pipeline '%s' (cluster '%s') is null.",
                                    stepIdInMap, pipelineName, clusterConfig.clusterName()));
                            continue;
                        }
                        if (step.pipelineStepId() == null || step.pipelineStepId().isBlank()){
                            errors.add(String.format("Pipeline step with key '%s' in pipeline '%s' (cluster '%s') has a null or blank pipelineStepId field.",
                                    stepIdInMap, pipelineName, clusterConfig.clusterName()));
                        } else {
                            // Check if the step ID matches the map key
                            if (!stepIdInMap.equals(step.pipelineStepId())){
                                errors.add(String.format("Pipeline step key '%s' does not match its pipelineStepId field '%s' in pipeline '%s' (cluster '%s').",
                                        stepIdInMap, step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
                            }

                            // Check for step ID uniqueness within the pipeline
                            // This check is performed regardless of whether the step ID matches the map key
                            if (!stepIds.add(step.pipelineStepId())) {
                                errors.add(String.format("Duplicate step ID '%s' found in pipeline '%s' (cluster '%s'). Step IDs must be unique within a pipeline.", 
                                        step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
                            }
                        }

                        // Check pipelineImplementationId
                        if (step.pipelineImplementationId() == null || step.pipelineImplementationId().isBlank()) {
                            errors.add(String.format("Pipeline step '%s' in pipeline '%s' (cluster '%s') has a null or blank pipelineImplementationId.",
                                    step.pipelineStepId(), pipelineName, clusterConfig.clusterName()));
                        } else if (!availableModules.containsKey(step.pipelineImplementationId())) {
                            errors.add(String.format("Pipeline step '%s' in pipeline '%s' (cluster '%s') references unknown pipelineImplementationId '%s'.",
                                    step.pipelineStepId(), pipelineName, clusterConfig.clusterName(), step.pipelineImplementationId()));
                        } else {
                            // Check if customConfigSchemaReference within the resolved module is itself valid (basic check)
                            // This is already handled by the SchemaReference record constructor,
                            // but double-checking here or ensuring modules always have valid refs is okay.
                            PipelineModuleConfiguration module = availableModules.get(step.pipelineImplementationId());
                            if (module.customConfigSchemaReference() != null) {
                                SchemaReference ref = module.customConfigSchemaReference();
                                // The SchemaReference record constructor already validates subject and version > 0.
                                // So, if a SchemaReference object exists and is non-null, it's internally valid.
                                // No need for: ref.subject() == null || ref.subject().isBlank() || ref.version() == null || ref.version() < 1
                                // unless you want to be extremely defensive against somehow bypassing record constructor validation.
                            }
                        }
                    }
                }
            }
        }
        return errors;
    }
}
