package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.*;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Validates that step types are used correctly within a pipeline.
 * <br/>
 * This validator ensures that:
 * 1. INITIAL_PIPELINE steps are not referenced by any other steps (they are true entry points)
 * 2. SINK steps don't have any next steps or error steps (they are true terminal points)
 */
@Singleton
public class StepTypeValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(StepTypeValidator.class);

    @Override
    public List<String> validate(PipelineClusterConfig clusterConfig,
                                 Function<SchemaReference, Optional<String>> schemaContentProvider) {
        List<String> errors = new ArrayList<>();

        if (clusterConfig == null || clusterConfig.pipelineGraphConfig() == null) {
            return errors; // Nothing to validate
        }

        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipeline = pipelineEntry.getValue();

            if (pipeline == null || pipeline.pipelineSteps() == null) {
                continue; // Skip invalid pipelines
            }

            // Collect all step IDs that are referenced as next steps or error steps
            Set<String> referencedStepIds = new HashSet<>();
            for (PipelineStepConfig step : pipeline.pipelineSteps().values()) {
                if (step != null) {
                    referencedStepIds.addAll(step.nextSteps());
                    referencedStepIds.addAll(step.errorSteps());
                }
            }

            // Validate each step
            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipeline.pipelineSteps().entrySet()) {
                String stepId = stepEntry.getKey();
                PipelineStepConfig step = stepEntry.getValue();

                if (step == null) {
                    continue; // Skip invalid steps
                }

                String stepContext = String.format("Step '%s' in pipeline '%s' (cluster '%s')",
                        stepId, pipelineName, clusterConfig.clusterName());

                // Validate INITIAL_PIPELINE steps are not referenced by other steps
                if (step.stepType() == StepType.INITIAL_PIPELINE && referencedStepIds.contains(stepId)) {
                    errors.add(String.format("%s: is marked as INITIAL_PIPELINE but is referenced by other steps, which is not allowed.", 
                            stepContext));
                }

                // Validate SINK steps don't have next steps or error steps
                if (step.stepType() == StepType.SINK && (!step.nextSteps().isEmpty() || !step.errorSteps().isEmpty())) {
                    errors.add(String.format("%s: is marked as SINK but has next steps or error steps, which is not allowed.", 
                            stepContext));
                }
            }
        }

        return errors;
    }
}