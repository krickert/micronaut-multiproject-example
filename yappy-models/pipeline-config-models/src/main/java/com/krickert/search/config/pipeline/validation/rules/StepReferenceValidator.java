package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.*;

/**
 * Validates that all step references point to existing steps within the pipeline.
 * This ensures referential integrity for gRPC service references.
 */
public class StepReferenceValidator implements StructuralValidationRule {
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            return errors;
        }
        
        // Collect all valid step IDs
        Set<String> validStepIds = new HashSet<>(config.pipelineSteps().keySet());
        
        // Check for duplicate step names
        Set<String> stepNames = new HashSet<>();
        for (var entry : config.pipelineSteps().entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step != null && step.stepName() != null && !step.stepName().isBlank()) {
                if (!stepNames.add(step.stepName())) {
                    errors.add("Duplicate step name found: " + step.stepName());
                }
            }
        }
        
        // Validate all references
        for (var entry : config.pipelineSteps().entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step != null && step.outputs() != null) {
                for (var outputEntry : step.outputs().entrySet()) {
                    String outputKey = outputEntry.getKey();
                    var output = outputEntry.getValue();
                    
                    if (output != null && 
                        output.transportType() == TransportType.GRPC && 
                        output.grpcTransport() != null && 
                        output.grpcTransport().serviceName() != null &&
                        !output.grpcTransport().serviceName().isBlank()) {
                        
                        String targetService = output.grpcTransport().serviceName();
                        
                        // Check if this looks like an internal reference (no dots, suggesting it's not a FQDN)
                        if (!targetService.contains(".") && !validStepIds.contains(targetService)) {
                            errors.add("Step '" + stepId + "' output '" + outputKey + 
                                     "' references non-existent target step '" + targetService + "'");
                        }
                    }
                }
            }
        }
        
        return errors;
    }
}