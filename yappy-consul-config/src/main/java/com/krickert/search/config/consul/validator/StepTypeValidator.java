package com.krickert.search.config.consul.validator;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.SchemaReference;
import com.krickert.search.config.pipeline.model.StepType; // Ensure this is your enum: INITIAL_PIPELINE, SINK, PIPELINE
import io.micronaut.core.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class StepTypeValidator implements ClusterValidationRule {
    private static final Logger LOG = LoggerFactory.getLogger(StepTypeValidator.class);

    @Override
    public List<String> validate(
            PipelineClusterConfig clusterConfig,
            Function<SchemaReference, Optional<String>> schemaContentProvider) {

        List<String> errors = new ArrayList<>();

        if (clusterConfig == null || clusterConfig.pipelineGraphConfig() == null || CollectionUtils.isEmpty(clusterConfig.pipelineGraphConfig().pipelines())) {
            return errors;
        }

        for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
            String pipelineName = pipelineEntry.getKey();
            PipelineConfig pipelineConfig = pipelineEntry.getValue();

            if (pipelineConfig == null || CollectionUtils.isEmpty(pipelineConfig.pipelineSteps())) {
                continue;
            }

            for (Map.Entry<String, PipelineStepConfig> stepEntry : pipelineConfig.pipelineSteps().entrySet()) {
                PipelineStepConfig stepConfig = stepEntry.getValue();

                if (stepConfig == null) {
                    errors.add(String.format(
                        "Pipeline '%s', Step key '%s': Contains invalid step definition (null).",
                        pipelineName, stepEntry.getKey()
                    ));
                    continue;
                }

                if (stepConfig.stepName() == null || stepConfig.stepName().isBlank() || stepConfig.stepType() == null) {
                    errors.add(String.format(
                        "Pipeline '%s', Step key '%s': Contains invalid step definition (missing name or type).",
                        pipelineName, stepEntry.getKey()
                    ));
                    continue;
                }

                String stepName = stepConfig.stepName();
                StepType stepType = stepConfig.stepType();

                boolean hasKafkaInputs = CollectionUtils.isNotEmpty(stepConfig.kafkaInputs());
                boolean hasOutputs = CollectionUtils.isNotEmpty(stepConfig.outputs());

                switch (stepType) {
                    case INITIAL_PIPELINE: // Changed from SOURCE
                        if (hasKafkaInputs) {
                            errors.add(String.format(
                                "Pipeline '%s', Step '%s' of type INITIAL_PIPELINE: must not have kafkaInputs defined. Found %d.",
                                pipelineName, stepName, stepConfig.kafkaInputs() != null ? stepConfig.kafkaInputs().size() : 0
                            ));
                        }
                        if (!hasOutputs) {
                            errors.add(String.format(
                                "Pipeline '%s', Step '%s' of type INITIAL_PIPELINE: should ideally have outputs defined.",
                                pipelineName, stepName
                            ));
                        }
                        break;

                    case SINK:
                        // Logic for SINK remains the same (no outputs, ideally has inputs)
                        if (!hasKafkaInputs && (stepConfig.processorInfo() == null || stepConfig.processorInfo().internalProcessorBeanName() == null || stepConfig.processorInfo().internalProcessorBeanName().isBlank())) {
                            errors.add(String.format(
                                "Pipeline '%s', Step '%s' of type SINK: should ideally have kafkaInputs defined or be an internal processor that receives data via other pipeline steps.",
                                pipelineName, stepName
                            ));
                        }
                        if (hasOutputs) {
                            errors.add(String.format(
                                "Pipeline '%s', Step '%s' of type SINK: must not have any outputs defined. Found %d.",
                                pipelineName, stepName, stepConfig.outputs() != null ? stepConfig.outputs().size() : 0
                            ));
                        }
                        break;

                    case PIPELINE:
                        // Logic for PIPELINE remains the same
                        if (!hasKafkaInputs && !hasOutputs && (stepConfig.processorInfo() == null || stepConfig.processorInfo().internalProcessorBeanName() == null || stepConfig.processorInfo().internalProcessorBeanName().isBlank())) {
                            // A PIPELINE step might not have direct Kafka inputs if it's fed by another step's output (e.g., gRPC).
                            // And it might not have outputs if it's an internal endpoint or a terminal processing step not formally a SINK.
                            // This condition is trying to catch truly orphaned PIPELINE steps.
                            // A more robust check would involve graph analysis (is it targeted by any output, do its outputs lead anywhere).
                            // For now, this logs. The current StepTypeValidator code in the previous response had a LOG.debug for this.
                            // If you want to make it an error:
                            // errors.add(String.format(
                            // "Pipeline '%s', Step '%s' of type PIPELINE: has no Kafka inputs and no defined outputs, and is not clearly an internal gRPC service. It may be orphaned or misconfigured.",
                            // pipelineName, stepName
                            // ));
                            LOG.debug("Pipeline '{}', Step '{}' of type PIPELINE has no Kafka inputs and no defined outputs. It might be an internal processing step or targeted by another step's output.", pipelineName, stepName);
                        }
                        break;

                    default:
                        errors.add(String.format(
                            "Pipeline '%s', Step '%s': Encountered an unknown or unhandled StepType '%s'.",
                            pipelineName, stepName, stepType
                        ));
                        break;
                }
            }
        }
        return errors;
    }
}
