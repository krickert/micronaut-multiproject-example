package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates that Kafka topics follow the expected naming convention.
 * This is a structural check that doesn't require whitelist data.
 * 
 * Expected convention: yappy.pipeline.{pipelineName}.step.{stepName}.{type}
 * Where type is: output, input, error, dead-letter
 */
public class KafkaTopicNamingValidator implements StructuralValidationRule {
    
    private static final Pattern TOPIC_CONVENTION_PATTERN = Pattern.compile(
        "yappy\\.pipeline\\.([^.]+)\\.step\\.([^.]+)\\.(input|output|error|dead-letter)"
    );
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.pipelineSteps() == null) {
            return errors;
        }
        
        for (var entry : config.pipelineSteps().entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step == null) {
                continue;
            }
            
            String stepName = step.stepName() != null ? step.stepName() : stepId;
            
            // Check Kafka outputs
            if (step.outputs() != null) {
                for (var outputEntry : step.outputs().entrySet()) {
                    var output = outputEntry.getValue();
                    if (output != null && 
                        output.transportType() == TransportType.KAFKA &&
                        output.kafkaTransport() != null &&
                        output.kafkaTransport().topic() != null) {
                        
                        String topic = output.kafkaTransport().topic();
                        validateTopicNaming(topic, pipelineId, stepName, "output", errors);
                    }
                }
            }
            
            // Check Kafka inputs
            if (step.kafkaInputs() != null) {
                for (int i = 0; i < step.kafkaInputs().size(); i++) {
                    KafkaInputDefinition input = step.kafkaInputs().get(i);
                    if (input != null && input.listenTopics() != null) {
                        for (String topic : input.listenTopics()) {
                            if (topic != null && !topic.isBlank()) {
                                validateTopicNaming(topic, pipelineId, stepName, "input", errors);
                            }
                        }
                    }
                }
            }
        }
        
        return errors;
    }
    
    private void validateTopicNaming(String topic, String pipelineId, String stepName, 
                                   String expectedType, List<String> errors) {
        // Skip validation if topic contains unresolved variables
        if (topic.contains("${")) {
            return; // Can't validate templates
        }
        
        // Check if it matches the convention
        var matcher = TOPIC_CONVENTION_PATTERN.matcher(topic);
        if (!matcher.matches()) {
            errors.add("Topic '" + topic + "' in step '" + stepName + 
                     "' does not follow naming convention: yappy.pipeline.{pipeline}.step.{step}.{type}");
            return;
        }
        
        // Extract parts and verify they match context
        String topicPipeline = matcher.group(1);
        String topicStep = matcher.group(2);
        String topicType = matcher.group(3);
        
        // For structural validation, we check if the pipeline name in the topic matches
        if (!topicPipeline.equals(pipelineId)) {
            errors.add("Topic '" + topic + "' references pipeline '" + topicPipeline + 
                     "' but is defined in pipeline '" + pipelineId + "'");
        }
        
        // For outputs, the step name should match
        if ("output".equals(expectedType) && !topicStep.equals(stepName)) {
            errors.add("Topic '" + topic + "' references step '" + topicStep + 
                     "' but is defined in step '" + stepName + "'");
        }
    }
}