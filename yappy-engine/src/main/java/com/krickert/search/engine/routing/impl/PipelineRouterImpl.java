package com.krickert.search.engine.routing.impl;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.engine.routing.PipelineRouter;
import com.krickert.search.model.PipeStream;
import com.krickert.search.model.StepExecutionRecord;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default implementation of PipelineRouter that handles sequential
 * and conditional routing through pipeline steps.
 */
@Singleton
public class PipelineRouterImpl implements PipelineRouter {
    
    private static final Logger LOG = LoggerFactory.getLogger(PipelineRouterImpl.class);
    
    @Override
    public Optional<PipelineStepConfig> getNextStep(PipeStream pipeStream, PipelineConfig pipelineConfig) {
        if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null || pipelineConfig.pipelineSteps().isEmpty()) {
            LOG.debug("No steps defined in pipeline configuration");
            return Optional.empty();
        }
        
        // Get the target step from the PipeStream
        String targetStepName = pipeStream.getTargetStepName();
        if (targetStepName != null && !targetStepName.isEmpty()) {
            PipelineStepConfig targetStep = pipelineConfig.pipelineSteps().get(targetStepName);
            if (targetStep != null) {
                LOG.debug("Using target step from PipeStream: {}", targetStepName);
                return Optional.of(targetStep);
            }
        }
        
        // Get the execution history
        List<StepExecutionRecord> history = pipeStream.getHistoryList();
        Set<String> executedSteps = history.stream()
                .map(StepExecutionRecord::getStepName)
                .collect(Collectors.toSet());
        
        // Find the first step that hasn't been executed yet
        // Since it's a Map, we need to maintain order somehow - use the step names in alphabetical order
        // or rely on the target_step_name in PipeStream
        for (Map.Entry<String, PipelineStepConfig> entry : pipelineConfig.pipelineSteps().entrySet()) {
            PipelineStepConfig step = entry.getValue();
            if (!executedSteps.contains(step.stepName())) {
                // Check if this step's dependencies have been met
                if (areDependenciesMet(step, executedSteps)) {
                    LOG.debug("Next step to execute: {}", step.stepName());
                    return Optional.of(step);
                } else {
                    LOG.debug("Step {} has unmet dependencies", step.stepName());
                }
            }
        }
        
        LOG.debug("All steps have been executed or have unmet dependencies");
        return Optional.empty();
    }
    
    @Override
    public boolean isPipelineComplete(PipeStream pipeStream, PipelineConfig pipelineConfig) {
        if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null) {
            return true;
        }
        
        // Check if there's a critical error
        if (pipeStream.hasStreamErrorData()) {
            LOG.debug("Pipeline execution stopped due to error");
            return true;
        }
        
        // Check if all required steps have been executed
        List<StepExecutionRecord> history = pipeStream.getHistoryList();
        Set<String> executedSteps = history.stream()
                .map(StepExecutionRecord::getStepName)
                .collect(Collectors.toSet());
        
        // Get all required steps (non-optional)
        Set<String> requiredSteps = pipelineConfig.pipelineSteps().values().stream()
                .filter(step -> !isOptionalStep(step))
                .map(step -> step.stepName())
                .collect(Collectors.toSet());
        
        boolean allRequiredStepsExecuted = executedSteps.containsAll(requiredSteps);
        
        if (allRequiredStepsExecuted) {
            LOG.debug("All required steps have been executed");
        }
        
        return allRequiredStepsExecuted;
    }
    
    @Override
    public Optional<String> getCurrentStep(PipeStream pipeStream) {
        List<StepExecutionRecord> history = pipeStream.getHistoryList();
        if (history.isEmpty()) {
            return Optional.empty();
        }
        
        // Get the most recent step that is still in progress
        for (int i = history.size() - 1; i >= 0; i--) {
            StepExecutionRecord record = history.get(i);
            // If the step doesn't have an end time, it's still in progress
            if (!record.hasEndTime()) {
                return Optional.of(record.getStepName());
            }
        }
        
        return Optional.empty();
    }
    
    @Override
    public Mono<RouterValidationResult> validatePipeline(PipelineConfig pipelineConfig, List<String> availableModules) {
        return Mono.fromCallable(() -> {
            List<String> errors = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            
            if (pipelineConfig == null) {
                errors.add("Pipeline configuration is null");
                return RouterValidationResult.invalid(errors);
            }
            
            if (pipelineConfig.pipelineSteps() == null || pipelineConfig.pipelineSteps().isEmpty()) {
                errors.add("Pipeline has no steps defined");
                return RouterValidationResult.invalid(errors);
            }
            
            // Check that all required modules are available
            Set<String> availableModuleSet = new HashSet<>(availableModules);
            for (PipelineStepConfig step : pipelineConfig.pipelineSteps().values()) {
                ProcessorInfo processorInfo = step.processorInfo();
                if (processorInfo == null) {
                    errors.add("Step '" + step.stepName() + "' has no processorInfo");
                } else if (processorInfo.grpcServiceName() != null && !processorInfo.grpcServiceName().isBlank()) {
                    if (!availableModuleSet.contains(processorInfo.grpcServiceName())) {
                        errors.add("Step '" + step.stepName() + "' requires module '" + 
                                  processorInfo.grpcServiceName() + "' which is not available");
                    }
                }
            }
            
            // Check for circular dependencies
            if (hasCircularDependencies(pipelineConfig)) {
                errors.add("Pipeline has circular dependencies");
            }
            
            // Check for orphaned steps (steps that depend on non-existent steps)
            for (PipelineStepConfig step : pipelineConfig.pipelineSteps().values()) {
                if (step.customConfig() != null && step.customConfig().configParams() != null && 
                    step.customConfig().configParams().containsKey("dependsOn")) {
                    String[] dependencies = step.customConfig().configParams().get("dependsOn").split(",");
                    for (String dep : dependencies) {
                        if (!stepExists(dep.trim(), pipelineConfig)) {
                            errors.add("Step '" + step.stepName() + "' depends on non-existent step '" + dep.trim() + "'");
                        }
                    }
                }
            }
            
            if (!errors.isEmpty()) {
                return RouterValidationResult.invalid(errors);
            } else if (!warnings.isEmpty()) {
                return RouterValidationResult.withWarnings(warnings);
            } else {
                return RouterValidationResult.valid();
            }
        });
    }
    
    @Override
    public List<PipelineStepConfig> createExecutionPlan(PipelineConfig pipelineConfig) {
        if (pipelineConfig == null || pipelineConfig.pipelineSteps() == null) {
            return Collections.emptyList();
        }
        
        // For now, return steps in order they're defined
        // TODO: In the future, this could do topological sorting based on dependencies
        return new ArrayList<>(pipelineConfig.pipelineSteps().values());
    }
    
    @Override
    public boolean isStepExecuted(String stepName, List<StepExecutionRecord> executionHistory) {
        return executionHistory.stream()
                .anyMatch(record -> record.getStepName().equals(stepName));
    }
    
    private boolean areDependenciesMet(PipelineStepConfig step, Set<String> executedSteps) {
        if (step.customConfig() == null || step.customConfig().configParams() == null || 
            !step.customConfig().configParams().containsKey("dependsOn")) {
            // No dependencies, so they're met
            return true;
        }
        
        String dependsOnValue = step.customConfig().configParams().get("dependsOn");
        if (dependsOnValue == null || dependsOnValue.isBlank()) {
            return true;
        }
        
        // Parse comma-separated dependencies
        String[] dependencies = dependsOnValue.split(",");
        for (String dependency : dependencies) {
            if (!executedSteps.contains(dependency.trim())) {
                return false;
            }
        }
        
        return true;
    }
    
    private boolean isOptionalStep(PipelineStepConfig step) {
        return step.customConfig() != null && 
               step.customConfig().configParams() != null &&
               step.customConfig().configParams().containsKey("optional") &&
               "true".equalsIgnoreCase(step.customConfig().configParams().get("optional"));
    }
    
    private boolean hasCircularDependencies(PipelineConfig pipelineConfig) {
        // Build dependency graph
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (PipelineStepConfig step : pipelineConfig.pipelineSteps().values()) {
            dependencies.put(step.stepName(), getDependencies(step));
        }
        
        // Check for cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String step : dependencies.keySet()) {
            if (hasCycle(step, dependencies, visited, recursionStack)) {
                return true;
            }
        }
        
        return false;
    }
    
    private boolean hasCycle(String step, Map<String, Set<String>> dependencies,
                            Set<String> visited, Set<String> recursionStack) {
        visited.add(step);
        recursionStack.add(step);
        
        Set<String> stepDeps = dependencies.get(step);
        if (stepDeps != null) {
            for (String dep : stepDeps) {
                if (!visited.contains(dep)) {
                    if (hasCycle(dep, dependencies, visited, recursionStack)) {
                        return true;
                    }
                } else if (recursionStack.contains(dep)) {
                    return true;
                }
            }
        }
        
        recursionStack.remove(step);
        return false;
    }
    
    private Set<String> getDependencies(PipelineStepConfig step) {
        if (step.customConfig() == null || step.customConfig().configParams() == null || 
            !step.customConfig().configParams().containsKey("dependsOn")) {
            return Collections.emptySet();
        }
        
        String dependsOnValue = step.customConfig().configParams().get("dependsOn");
        if (dependsOnValue == null || dependsOnValue.isBlank()) {
            return Collections.emptySet();
        }
        
        return Arrays.stream(dependsOnValue.split(","))
                .map(String::trim)
                .collect(Collectors.toSet());
    }
    
    private boolean stepExists(String stepName, PipelineConfig pipelineConfig) {
        return pipelineConfig.pipelineSteps().containsKey(stepName);
    }
}