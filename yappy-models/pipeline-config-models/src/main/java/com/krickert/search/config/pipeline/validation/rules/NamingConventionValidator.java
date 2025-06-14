package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates naming conventions for pipelines and steps.
 * Ensures IDs follow the required pattern and reserved names aren't used.
 */
public class NamingConventionValidator implements StructuralValidationRule {
    
    private static final Pattern PIPELINE_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern STEP_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    
    private static final Set<String> RESERVED_NAMES = Set.of(
            "default", "system", "admin", "config", "consul", "kafka", "yappy"
    );
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        // Validate pipeline ID
        if (pipelineId == null || pipelineId.isBlank()) {
            errors.add("Pipeline ID is required");
        } else if (!PIPELINE_ID_PATTERN.matcher(pipelineId).matches()) {
            errors.add("Pipeline ID must start with lowercase letter and contain only lowercase letters, numbers, and hyphens");
        } else if (RESERVED_NAMES.contains(pipelineId)) {
            errors.add("Pipeline ID '" + pipelineId + "' is a reserved name");
        }
        
        // Validate pipeline name
        if (config.name() == null || config.name().isBlank()) {
            errors.add("Pipeline name is required");
        }
        
        // Validate step IDs and names
        if (config.pipelineSteps() != null) {
            for (var entry : config.pipelineSteps().entrySet()) {
                String stepId = entry.getKey();
                PipelineStepConfig step = entry.getValue();
                
                // Validate step ID format
                if (!STEP_ID_PATTERN.matcher(stepId).matches()) {
                    errors.add("Step ID '" + stepId + "' must start with lowercase letter and contain only lowercase letters, numbers, and hyphens");
                }
                
                if (step != null) {
                    // Validate service name format in processorInfo
                    if (step.processorInfo() != null) {
                        if (step.processorInfo().grpcServiceName() != null && !step.processorInfo().grpcServiceName().isBlank()) {
                            String serviceName = step.processorInfo().grpcServiceName();
                            if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
                                errors.add("gRPC service name '" + serviceName + "' in step '" + stepId + "' must follow service naming pattern");
                            }
                        }
                    }
                    
                    // Ensure step ID matches step name if present
                    if (step.stepName() != null && !step.stepName().isBlank() && !stepId.equals(step.stepName())) {
                        errors.add("Step ID '" + stepId + "' does not match step name '" + step.stepName() + "'");
                    }
                }
            }
        }
        
        return errors;
    }
}