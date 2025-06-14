package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that step types are used correctly based on their purpose.
 * - INITIAL_PIPELINE steps should not have Kafka inputs
 * - SINK steps should not have outputs
 * - PIPELINE steps should have either inputs or outputs (or be internal processors)
 */
public class StepTypeValidator implements StructuralValidationRule {
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            return errors;
        }
        
        for (var entry : config.pipelineSteps().entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step == null || step.stepType() == null) {
                continue; // RequiredFieldsValidator will catch this
            }
            
            String stepName = step.stepName() != null ? step.stepName() : stepId;
            StepType stepType = step.stepType();
            
            boolean hasKafkaInputs = step.kafkaInputs() != null && !step.kafkaInputs().isEmpty();
            boolean hasOutputs = step.outputs() != null && !step.outputs().isEmpty();
            boolean hasInternalProcessor = step.processorInfo() != null && 
                                         step.processorInfo().internalProcessorBeanName() != null &&
                                         !step.processorInfo().internalProcessorBeanName().isBlank();
            
            switch (stepType) {
                case INITIAL_PIPELINE:
                    if (hasKafkaInputs) {
                        errors.add("Step '" + stepName + "' of type INITIAL_PIPELINE must not have Kafka inputs");
                    }
                    if (!hasOutputs && !hasInternalProcessor) {
                        errors.add("Step '" + stepName + "' of type INITIAL_PIPELINE should have outputs defined");
                    }
                    break;
                    
                case SINK:
                    if (hasOutputs) {
                        errors.add("Step '" + stepName + "' of type SINK must not have any outputs");
                    }
                    if (!hasKafkaInputs && !hasInternalProcessor) {
                        errors.add("Step '" + stepName + "' of type SINK should have Kafka inputs or be an internal processor");
                    }
                    break;
                    
                case PIPELINE:
                    // PIPELINE steps are flexible - they can have inputs, outputs, both, or neither (if internal)
                    // Just log a warning if they seem orphaned
                    if (!hasKafkaInputs && !hasOutputs && !hasInternalProcessor) {
                        errors.add("Step '" + stepName + "' of type PIPELINE has no inputs, outputs, or internal processor - it may be orphaned");
                    }
                    break;
                    
                default:
                    errors.add("Step '" + stepName + "' has unknown StepType: " + stepType);
                    break;
            }
        }
        
        return errors;
    }
}