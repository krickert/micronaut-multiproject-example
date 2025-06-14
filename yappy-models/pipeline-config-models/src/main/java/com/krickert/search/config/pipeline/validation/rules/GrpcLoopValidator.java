package com.krickert.search.config.pipeline.validation.rules;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.TransportType;
import com.krickert.search.config.pipeline.validation.StructuralValidationRule;

import java.util.*;

/**
 * Validates that there are no circular dependencies in gRPC flows within a pipeline.
 * This is a client-side structural check for intra-pipeline gRPC loops.
 */
public class GrpcLoopValidator implements StructuralValidationRule {
    
    @Override
    public List<String> validate(String pipelineId, PipelineConfig config) {
        List<String> errors = new ArrayList<>();
        
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            return errors; // No steps to validate
        }
        
        // Build adjacency list for gRPC connections
        Map<String, Set<String>> adjacencyList = buildGrpcAdjacencyList(config.pipelineSteps());
        
        // Detect cycles using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();
        
        for (String stepId : config.pipelineSteps().keySet()) {
            if (!visited.contains(stepId)) {
                List<String> cyclePath = new ArrayList<>();
                if (hasCycleDFS(stepId, adjacencyList, visited, recursionStack, cyclePath)) {
                    // Format cycle path for error message
                    Collections.reverse(cyclePath);
                    String cycleDescription = formatCyclePath(cyclePath);
                    errors.add("Circular dependency detected in gRPC flow: " + cycleDescription);
                }
            }
        }
        
        return errors;
    }
    
    /**
     * Builds an adjacency list representing gRPC connections between steps.
     */
    private Map<String, Set<String>> buildGrpcAdjacencyList(Map<String, PipelineStepConfig> steps) {
        Map<String, Set<String>> adjacencyList = new HashMap<>();
        
        for (var entry : steps.entrySet()) {
            String stepId = entry.getKey();
            PipelineStepConfig step = entry.getValue();
            
            if (step != null && step.outputs() != null) {
                Set<String> targets = new HashSet<>();
                
                for (var output : step.outputs().values()) {
                    if (output != null && 
                        output.transportType() == TransportType.GRPC && 
                        output.grpcTransport() != null && 
                        output.grpcTransport().serviceName() != null &&
                        !output.grpcTransport().serviceName().isBlank()) {
                        
                        String targetService = output.grpcTransport().serviceName();
                        // Only add if it's an internal reference (exists in steps)
                        if (steps.containsKey(targetService)) {
                            targets.add(targetService);
                        }
                    }
                }
                
                if (!targets.isEmpty()) {
                    adjacencyList.put(stepId, targets);
                }
            }
        }
        
        return adjacencyList;
    }
    
    /**
     * DFS helper to detect cycles.
     * @return true if a cycle is found
     */
    private boolean hasCycleDFS(String node, Map<String, Set<String>> adjacencyList, 
                               Set<String> visited, Set<String> recursionStack, 
                               List<String> cyclePath) {
        visited.add(node);
        recursionStack.add(node);
        
        Set<String> neighbors = adjacencyList.get(node);
        if (neighbors != null) {
            for (String neighbor : neighbors) {
                if (!visited.contains(neighbor)) {
                    if (hasCycleDFS(neighbor, adjacencyList, visited, recursionStack, cyclePath)) {
                        cyclePath.add(node);
                        return true;
                    }
                } else if (recursionStack.contains(neighbor)) {
                    // Found a cycle
                    cyclePath.add(neighbor);
                    cyclePath.add(node);
                    return true;
                }
            }
        }
        
        recursionStack.remove(node);
        return false;
    }
    
    /**
     * Formats a cycle path for display in error messages.
     */
    private String formatCyclePath(List<String> cyclePath) {
        if (cyclePath.isEmpty()) {
            return "[empty cycle]";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        sb.append(String.join(" -> ", cyclePath));
        
        // Complete the cycle by showing it returns to the first step
        if (!cyclePath.isEmpty()) {
            sb.append(" -> ").append(cyclePath.get(0));
        }
        
        sb.append("]");
        return sb.toString();
    }
}