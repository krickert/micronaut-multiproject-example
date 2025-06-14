package com.krickert.search.config.pipeline.validation;

import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.validation.rules.*;

import java.util.*;

/**
 * Main structural validator for pipeline configurations.
 * Orchestrates multiple validation rules to perform comprehensive client-side validation.
 * This validator can be used before sending configurations to the server.
 */
public class PipelineStructuralValidator {
    
    // Default validation rules for single pipeline validation
    private static final List<StructuralValidationRule> DEFAULT_PIPELINE_RULES = List.of(
        new NamingConventionValidator(),
        new RequiredFieldsValidator(),
        new StepTypeValidator(),
        new StepReferenceValidator(),
        new KafkaTopicNamingValidator(),
        new IntraPipelineLoopValidator()
    );
    
    // Default validation rules for cluster-wide validation
    private static final List<ClusterStructuralValidationRule> DEFAULT_CLUSTER_RULES = List.of(
        new InterPipelineLoopValidator()
    );
    
    /**
     * Validates a single pipeline configuration using all default rules.
     * 
     * @param pipelineId The ID of the pipeline
     * @param config The pipeline configuration to validate
     * @return Validation result with any errors found
     */
    public static StructuralValidationResult validatePipeline(String pipelineId, PipelineConfig config) {
        return validatePipeline(pipelineId, config, DEFAULT_PIPELINE_RULES);
    }
    
    /**
     * Validates a single pipeline configuration using specified rules.
     * 
     * @param pipelineId The ID of the pipeline
     * @param config The pipeline configuration to validate
     * @param rules The validation rules to apply
     * @return Validation result with any errors found
     */
    public static StructuralValidationResult validatePipeline(String pipelineId, PipelineConfig config, 
                                                             List<StructuralValidationRule> rules) {
        if (config == null) {
            return StructuralValidationResult.invalid("Pipeline configuration is null");
        }
        
        List<String> allErrors = new ArrayList<>();
        
        for (StructuralValidationRule rule : rules) {
            try {
                List<String> ruleErrors = rule.validate(pipelineId, config);
                if (ruleErrors != null && !ruleErrors.isEmpty()) {
                    // Prefix errors with rule name for clarity
                    String ruleName = rule.getRuleName();
                    for (String error : ruleErrors) {
                        allErrors.add("[" + ruleName + "] " + error);
                    }
                }
            } catch (Exception e) {
                allErrors.add("[" + rule.getRuleName() + "] Validation error: " + e.getMessage());
            }
        }
        
        return allErrors.isEmpty() 
            ? StructuralValidationResult.validResult() 
            : StructuralValidationResult.invalid(allErrors);
    }
    
    /**
     * Validates a cluster configuration using all default rules.
     * This includes both pipeline-specific and cluster-wide validations.
     * 
     * @param clusterConfig The cluster configuration to validate
     * @return Validation result with any errors found
     */
    public static StructuralValidationResult validateCluster(PipelineClusterConfig clusterConfig) {
        return validateCluster(clusterConfig, DEFAULT_PIPELINE_RULES, DEFAULT_CLUSTER_RULES);
    }
    
    /**
     * Validates a cluster configuration using specified rules.
     * 
     * @param clusterConfig The cluster configuration to validate
     * @param pipelineRules Rules to apply to each pipeline
     * @param clusterRules Rules to apply across the cluster
     * @return Validation result with any errors found
     */
    public static StructuralValidationResult validateCluster(PipelineClusterConfig clusterConfig,
                                                           List<StructuralValidationRule> pipelineRules,
                                                           List<ClusterStructuralValidationRule> clusterRules) {
        if (clusterConfig == null) {
            return StructuralValidationResult.invalid("Cluster configuration is null");
        }
        
        List<String> allErrors = new ArrayList<>();
        
        // Validate cluster name
        if (clusterConfig.clusterName() == null || clusterConfig.clusterName().isBlank()) {
            allErrors.add("Cluster name is required");
        }
        
        // Validate each pipeline
        if (clusterConfig.pipelineGraphConfig() != null && 
            clusterConfig.pipelineGraphConfig().pipelines() != null) {
            
            for (var entry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
                String pipelineId = entry.getKey();
                PipelineConfig pipeline = entry.getValue();
                
                StructuralValidationResult pipelineResult = validatePipeline(pipelineId, pipeline, pipelineRules);
                if (!pipelineResult.valid()) {
                    for (String error : pipelineResult.errors()) {
                        allErrors.add("Pipeline '" + pipelineId + "': " + error);
                    }
                }
            }
        }
        
        // Apply cluster-wide validation rules
        for (ClusterStructuralValidationRule rule : clusterRules) {
            try {
                List<String> ruleErrors = rule.validate(clusterConfig);
                if (ruleErrors != null && !ruleErrors.isEmpty()) {
                    String ruleName = rule.getRuleName();
                    for (String error : ruleErrors) {
                        allErrors.add("[" + ruleName + "] " + error);
                    }
                }
            } catch (Exception e) {
                allErrors.add("[" + rule.getRuleName() + "] Validation error: " + e.getMessage());
            }
        }
        
        return allErrors.isEmpty() 
            ? StructuralValidationResult.validResult() 
            : StructuralValidationResult.invalid(allErrors);
    }
    
    /**
     * Validates that a service name follows naming conventions.
     * 
     * @param serviceName The service name to validate
     * @return Validation result
     */
    public static StructuralValidationResult validateServiceName(String serviceName) {
        List<String> errors = new ArrayList<>();
        
        if (serviceName == null || serviceName.isBlank()) {
            errors.add("Service name is required");
        } else if (!serviceName.matches("^[a-z][a-z0-9-]*$")) {
            errors.add("Service name must start with lowercase letter and contain only lowercase letters, numbers, and hyphens");
        } else if (serviceName.length() > 63) {
            errors.add("Service name cannot exceed 63 characters");
        }
        
        return errors.isEmpty() 
            ? StructuralValidationResult.validResult() 
            : StructuralValidationResult.invalid(errors);
    }
    
    /**
     * Validates that a Kafka topic name follows conventions.
     * 
     * @param topicName The topic name to validate
     * @return Validation result
     */
    public static StructuralValidationResult validateKafkaTopic(String topicName) {
        List<String> errors = new ArrayList<>();
        
        if (topicName == null || topicName.isBlank()) {
            errors.add("Topic name is required");
        } else if (!topicName.matches("^[a-zA-Z0-9._-]+$")) {
            errors.add("Topic name can only contain letters, numbers, dots, underscores, and hyphens");
        } else if (topicName.length() > 249) {
            errors.add("Topic name cannot exceed 249 characters");
        } else if (topicName.equals(".") || topicName.equals("..")) {
            errors.add("Topic name cannot be '.' or '..'");
        }
        
        return errors.isEmpty() 
            ? StructuralValidationResult.validResult() 
            : StructuralValidationResult.invalid(errors);
    }
}