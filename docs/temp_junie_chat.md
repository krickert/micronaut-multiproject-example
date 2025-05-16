
# Solution for Implementing Step Types in YAPPY

Based on the requirements and analysis of the codebase, I'll provide a comprehensive solution to implement step types in YAPPY, removing the "suggested pipeline" logic from the gRPC layer and handling it in the config layer instead.

## 1. Create a `StepType` Enum

First, we need to create a new enum to represent different types of pipeline steps:

```java
package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Defines the type of a pipeline step, which affects its validation rules and behavior.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public enum StepType {
    /**
     * Standard pipeline step that can have both inputs and outputs.
     */
    PIPELINE,
    
    /**
     * Initial pipeline step that can only have outputs, not inputs.
     * These steps serve as entry points to the pipeline.
     */
    INITIAL_PIPELINE,
    
    /**
     * Terminal pipeline step that can have inputs but no outputs.
     */
    SINK
}
```

## 2. Update `PipelineStepConfig` to Include `stepType`

Next, we need to update the `PipelineStepConfig` record to include the new `stepType` field:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStepConfig(
    @JsonProperty("pipelineStepId") String pipelineStepId,
    @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
    @JsonProperty("customConfig") JsonConfigOptions customConfig,
    
    // Logical flow definition
    @JsonProperty("nextSteps") List<String> nextSteps,
    @JsonProperty("errorSteps") List<String> errorSteps,
    
    // Physical transport configuration
    @JsonProperty("transportType") TransportType transportType,
    @JsonProperty("kafkaConfig") KafkaTransportConfig kafkaConfig,
    @JsonProperty("grpcConfig") GrpcTransportConfig grpcConfig,
    
    // Step type (PIPELINE, INITIAL_PIPELINE, SINK)
    @JsonProperty("stepType") StepType stepType
) {
    @JsonCreator
    public PipelineStepConfig(
        @JsonProperty("pipelineStepId") String pipelineStepId,
        @JsonProperty("pipelineImplementationId") String pipelineImplementationId,
        @JsonProperty("customConfig") JsonConfigOptions customConfig,
        @JsonProperty("nextSteps") List<String> nextSteps,
        @JsonProperty("errorSteps") List<String> errorSteps,
        @JsonProperty("transportType") TransportType transportType,
        @JsonProperty("kafkaConfig") KafkaTransportConfig kafkaConfig,
        @JsonProperty("grpcConfig") GrpcTransportConfig grpcConfig,
        @JsonProperty("stepType") StepType stepType
    ) {
        // --- Essential Validations ---
        if (pipelineStepId == null || pipelineStepId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineStepId cannot be null or blank.");
        }
        this.pipelineStepId = pipelineStepId;

        if (pipelineImplementationId == null || pipelineImplementationId.isBlank()) {
            throw new IllegalArgumentException("PipelineStepConfig pipelineImplementationId cannot be null or blank.");
        }
        this.pipelineImplementationId = pipelineImplementationId;

        if (transportType == null) {
            throw new IllegalArgumentException("PipelineStepConfig transportType cannot be null.");
        }
        this.transportType = transportType;

        // Default to PIPELINE if stepType is null
        this.stepType = (stepType == null) ? StepType.PIPELINE : stepType;

        // --- Conditional Validations for Transport Configs ---
        if (this.transportType == TransportType.KAFKA && kafkaConfig == null) {
            throw new IllegalArgumentException("KafkaTransportConfig must be provided when transportType is KAFKA.");
        }
        if (this.transportType != TransportType.KAFKA && kafkaConfig != null) {
            throw new IllegalArgumentException("KafkaTransportConfig should only be provided when transportType is KAFKA (found type: " + this.transportType + ").");
        }
        this.kafkaConfig = kafkaConfig;

        if (this.transportType == TransportType.GRPC && grpcConfig == null) {
            throw new IllegalArgumentException("GrpcTransportConfig must be provided when transportType is GRPC.");
        }
        if (this.transportType != TransportType.GRPC && grpcConfig != null) {
            throw new IllegalArgumentException("GrpcTransportConfig should only be provided when transportType is GRPC (found type: " + this.transportType + ").");
        }
        this.grpcConfig = grpcConfig;

        // --- Custom Config can be null ---
        this.customConfig = customConfig;

        // --- Validate List Contents (no null elements) ---
        if (nextSteps != null) {
            for (String step : nextSteps) {
                if (step == null) {
                    throw new IllegalArgumentException("nextSteps cannot contain null or blank step IDs: " + nextSteps);
                }
            }
        }

        if (errorSteps != null) {
            for (String step : errorSteps) {
                if (step == null) {
                    throw new IllegalArgumentException("errorSteps cannot contain null or blank step IDs: " + errorSteps);
                }
            }
        }

        // --- Defensive Copies for Lists ---
        this.nextSteps = (nextSteps == null) ? Collections.emptyList() : List.copyOf(nextSteps);
        this.errorSteps = (errorSteps == null) ? Collections.emptyList() : List.copyOf(errorSteps);

        // --- Validate for blank step IDs ---
        for (String stepId : this.nextSteps) {
            if (stepId.isBlank()) {
                throw new IllegalArgumentException("nextSteps cannot contain null or blank step IDs: " + nextSteps);
            }
        }

        for (String stepId : this.errorSteps) {
            if (stepId.isBlank()) {
                throw new IllegalArgumentException("errorSteps cannot contain null or blank step IDs: " + errorSteps);
            }
        }

        // --- Step Type Specific Validations ---
        // Note: We don't validate that INITIAL_PIPELINE steps aren't referenced by other steps
        // or that SINK steps don't have next steps here, as that requires knowledge of the entire pipeline.
        // These validations will be handled by the StepTypeValidator.
    }
}
```

## 3. Create a New `StepTypeValidator` Class

Now, let's create a new validator to enforce the rules for different step types:

```java
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
```

## 4. Update the `DefaultConfigurationValidator` to Include the New Validator

We need to ensure our new validator is included in the validation process:

```java
// In DefaultConfigurationValidator.java, add the StepTypeValidator to the list of rules
@Singleton
public class DefaultConfigurationValidator implements ConfigurationValidator {
    private final List<ClusterValidationRule> rules;

    @Inject
    public DefaultConfigurationValidator(
            ReferentialIntegrityValidator referentialIntegrityValidator,
            WhitelistValidator whitelistValidator,
            IntraPipelineLoopValidator intraPipelineLoopValidator,
            InterPipelineLoopValidator interPipelineLoopValidator,
            CustomConfigSchemaValidator customConfigSchemaValidator,
            StepTypeValidator stepTypeValidator) { // Add this parameter
        
        // Add the new validator to the rules list
        this.rules = List.of(
                referentialIntegrityValidator,
                whitelistValidator,
                intraPipelineLoopValidator,
                interPipelineLoopValidator,
                customConfigSchemaValidator,
                stepTypeValidator  // Add this to the list
        );
    }
    
    // Rest of the class remains the same
}
```

## 5. Update the `IngestionService.findConnectorPipelineTarget` Method

Finally, let's update the `IngestionService.findConnectorPipelineTarget` method to use the new `StepType` instead of the current "suggested pipeline" logic:

```java
private ConnectorPipelineTarget findConnectorPipelineTarget(PipelineClusterConfig clusterConfig, String sourceIdentifier) {
    // Priority:
    // 1. Look for a pipeline with an INITIAL_PIPELINE step that matches the sourceIdentifier
    // 2. Use the default pipeline if specified
    
    if (clusterConfig.pipelineGraphConfig() == null) {
        LOG.warn("No pipeline graph configuration found.");
        return null;
    }
    
    // Search through all pipelines for an INITIAL_PIPELINE step that matches the sourceIdentifier
    for (Map.Entry<String, PipelineConfig> pipelineEntry : clusterConfig.pipelineGraphConfig().pipelines().entrySet()) {
        String pipelineName = pipelineEntry.getKey();
        PipelineConfig pipelineConfig = pipelineEntry.getValue();
        
        if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null) {
            continue;
        }
        
        for (Map.Entry<String, PipelineStepConfig> stepEntry : pipelineConfig.pipelineSteps().entrySet()) {
            String stepId = stepEntry.getKey();
            PipelineStepConfig stepConfig = stepEntry.getValue();
            
            if (stepConfig == null) {
                continue;
            }
            
            // Check if this is an INITIAL_PIPELINE step and if its ID matches the sourceIdentifier
            if (stepConfig.stepType() == StepType.INITIAL_PIPELINE && stepId.equals(sourceIdentifier)) {
                LOG.info("Found initial step with ID matching source_identifier {}: pipeline={}, step={}",
                    sourceIdentifier, pipelineName, stepId);
                return new ConnectorPipelineTarget(pipelineName, stepId);
            }
        }
    }
    
    // No specific initial step found for this sourceIdentifier
    // Fall back to the default pipeline if configured
    String defaultPipelineName = clusterConfig.defaultPipelineName();
    if (defaultPipelineName != null && !defaultPipelineName.isEmpty()) {
        PipelineConfig defaultPipelineConfig = clusterConfig.pipelineGraphConfig().getPipelineConfig(defaultPipelineName);
        
        if (defaultPipelineConfig != null && defaultPipelineConfig.pipelineSteps() != null) {
            // Find the first INITIAL_PIPELINE step in the default pipeline
            for (Map.Entry<String, PipelineStepConfig> stepEntry : defaultPipelineConfig.pipelineSteps().entrySet()) {
                String stepId = stepEntry.getKey();
                PipelineStepConfig stepConfig = stepEntry.getValue();
                
                if (stepConfig != null && stepConfig.stepType() == StepType.INITIAL_PIPELINE) {
                    LOG.info("Using default pipeline's initial step for source_identifier {}: pipeline={}, step={}",
                        sourceIdentifier, defaultPipelineName, stepId);
                    return new ConnectorPipelineTarget(defaultPipelineName, stepId);
                }
            }
            
            LOG.warn("Default pipeline '{}' has no INITIAL_PIPELINE steps.", defaultPipelineName);
        } else {
            LOG.warn("Default pipeline '{}' not found in configuration or has no steps.", defaultPipelineName);
        }
    }
    
    LOG.warn("No matching initial step found for source_identifier {} and no usable default pipeline.", sourceIdentifier);
    return null;
}
```

## 6. Update `PipelineClusterConfig` to Include `defaultPipelineName`

We need to add the `defaultPipelineName` field to the `PipelineClusterConfig` record:

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineClusterConfig(
        @JsonProperty("clusterName") String clusterName,
        @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
        @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
        @JsonProperty("defaultPipelineName") String defaultPipelineName,
        @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics,
        @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices
) {
    // Constructor implementation remains the same, just add defaultPipelineName to the parameters
    // and assign it directly (it can be null)
    @JsonCreator
    public PipelineClusterConfig(
            @JsonProperty("clusterName") String clusterName,
            @JsonProperty("pipelineGraphConfig") PipelineGraphConfig pipelineGraphConfig,
            @JsonProperty("pipelineModuleMap") PipelineModuleMap pipelineModuleMap,
            @JsonProperty("defaultPipelineName") String defaultPipelineName,
            @JsonProperty("allowedKafkaTopics") Set<String> allowedKafkaTopics,
            @JsonProperty("allowedGrpcServices") Set<String> allowedGrpcServices
    ) {
        // Existing validation code...
        
        this.clusterName = clusterName;
        this.pipelineGraphConfig = pipelineGraphConfig;
        this.pipelineModuleMap = pipelineModuleMap;
        this.defaultPipelineName = defaultPipelineName; // Can be null
        
        // Existing validation code for allowedKafkaTopics and allowedGrpcServices...
    }
}
```

## Summary of Changes

1. **Created `StepType` Enum**: Defines three types of steps - `PIPELINE`, `INITIAL_PIPELINE`, and `SINK`.
2. **Updated `PipelineStepConfig`**: Added the `stepType` field with a default value of `PIPELINE` for backward compatibility.
3. **Created `StepTypeValidator`**: A new validator that ensures:
    - `INITIAL_PIPELINE` steps are not referenced by any other steps
    - `SINK` steps don't have any next steps or error steps
4. **Updated `DefaultConfigurationValidator`**: Added the new `StepTypeValidator` to the list of validation rules.
5. **Updated `IngestionService.findConnectorPipelineTarget`**: Changed the method to use the new `StepType` instead of the current "suggested pipeline" logic.
6. **Updated `PipelineClusterConfig`**: Added the `defaultPipelineName` field to support fallback to a default pipeline.

These changes remove the "suggested pipeline" logic from the gRPC layer and handle it in the config layer instead. The initial registration of an initializer step is now nearly identical to any other step, but with the restriction of no inputs (enforced by the `StepTypeValidator`). Similarly, SINK steps are restricted to have no outputs.

This approach provides a clean, explicit way to define and validate pipeline steps with different roles, making it easy for a UI tool to create and modify these connections dynamically.