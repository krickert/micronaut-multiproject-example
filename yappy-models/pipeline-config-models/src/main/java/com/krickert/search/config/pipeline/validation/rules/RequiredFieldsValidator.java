package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that all required fields are present in the pipeline configuration.
 */
public class RequiredFieldsValidator implements StructuralValidationRule {
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        // Pipeline must have steps
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            errors.add("Pipeline must have at least one step");
            return errors; // No point checking steps if there are none
        }
        
        // Validate each step has required fields
        for (var entry : config.pipelineSteps().entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step == null) {
                errors.add("Step configuration is null for step ID: " + stepId);
                continue;
            }
            
            // Step name is required
            if (step.stepName() == null || step.stepName().isBlank()) {
                errors.add("Step name is required for step ID: " + stepId);
            }
            
            // Step type is required
            if (step.stepType() == null) {
                errors.add("Step type is required for step: " + stepId);
            }
            
            // Processor info is required
            if (step.processorInfo() == null) {
                errors.add("Processor info is required for step: " + stepId);
            } else if ((step.processorInfo().grpcServiceName() == null || step.processorInfo().grpcServiceName().isBlank()) &&
                      (step.processorInfo().internalProcessorBeanName() == null || step.processorInfo().internalProcessorBeanName().isBlank())) {
                errors.add("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName for step: " + stepId);
            }
            
            // Validate outputs if present
            if (step.outputs() != null) {
                for (var outputEntry : step.outputs().entrySet()) {
                    String outputKey = outputEntry.getKey();
                    var output = outputEntry.getValue();
                    
                    if (output == null) {
                        errors.add("Output target is null for step '" + stepId + "' output key '" + outputKey + "'");
                        continue;
                    }
                    
                    if (output.transportType() == null) {
                        errors.add("Transport type is required for step '" + stepId + "' output '" + outputKey + "'");
                    } else {
                        // Validate transport-specific requirements
                        switch (output.transportType()) {
                            case GRPC:
                                if (output.grpcTransport() == null) {
                                    errors.add("gRPC transport config is required for step '" + stepId + "' output '" + outputKey + "'");
                                }
                                break;
                            case KAFKA:
                                if (output.kafkaTransport() == null) {
                                    errors.add("Kafka transport config is required for step '" + stepId + "' output '" + outputKey + "'");
                                } else if (output.kafkaTransport().topic() == null || output.kafkaTransport().topic().isBlank()) {
                                    errors.add("Kafka topic is required for step '" + stepId + "' output '" + outputKey + "'");
                                }
                                break;
                        }
                    }
                }
            }
            
            // Validate Kafka inputs if present
            if (step.kafkaInputs() != null) {
                for (int i = 0; i < step.kafkaInputs().size(); i++) {
                    var input = step.kafkaInputs().get(i);
                    String inputRef = "step '" + stepId + "' Kafka input[" + i + "]";
                    
                    if (input == null) {
                        errors.add("Kafka input is null for " + inputRef);
                        continue;
                    }
                    
                    if (input.listenTopics() == null || input.listenTopics().isEmpty()) {
                        errors.add("Listen topics are required for " + inputRef);
                    }
                    
                    if (input.consumerGroupId() == null || input.consumerGroupId().isBlank()) {
                        errors.add("Consumer group ID is required for " + inputRef);
                    }
                }
            }
        }
        
        return errors;
    }
}