package com.krickert.search.pipeline.api.mapper;

import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.OutputTarget;
import com.krickert.search.pipeline.api.dto.CreatePipelineRequest;
import com.krickert.search.pipeline.api.dto.PipelineView;
import com.krickert.search.pipeline.api.dto.PipelineSummary;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps between internal pipeline models and API DTOs.
 * This is a simplified implementation for MVP.
 */
@Singleton
public class PipelineMapper {
    
    /**
     * Convert a CreatePipelineRequest to PipelineConfig.
     * This is a simplified mapping - the real PipelineStepConfig is much more complex.
     */
    public PipelineConfig fromRequest(CreatePipelineRequest request) {
        return toPipelineConfig(request);
    }
    
    /**
     * Convert a CreatePipelineRequest to PipelineConfig.
     * Creates proper pipeline step connections based on the 'next' field in step definitions.
     */
    public PipelineConfig toPipelineConfig(CreatePipelineRequest request) {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        
        // First, create basic step configurations
        Map<String, List<String>> stepConnections = new HashMap<>();
        for (var stepDef : request.steps()) {
            stepConnections.put(stepDef.id(), stepDef.next() != null ? stepDef.next() : List.of());
        }
        
        // Determine step types based on connections
        Set<String> hasIncomingConnections = new HashSet<>();
        for (var connections : stepConnections.values()) {
            hasIncomingConnections.addAll(connections);
        }
        
        // Map each step definition to a PipelineStepConfig with proper connections
        for (var stepDef : request.steps()) {
            StepType stepType = determineStepType(stepDef.id(), stepConnections, hasIncomingConnections);
            ProcessorInfo processorInfo = new ProcessorInfo(stepDef.module(), null); // Use gRPC service name
            
            // Create output targets for this step
            Map<String, OutputTarget> outputs = createOutputTargets(stepDef.next());
            
            var stepConfig = new PipelineStepConfig(
                    stepDef.id(),
                    stepType,
                    "Test Description for " + stepDef.id(), // description
                    null, // customConfigSchemaId
                    null, // customConfig - simplified for MVP
                    Collections.emptyList(), // kafkaInputs - simplified for MVP
                    outputs,
                    0, // maxRetries
                    1000L, // retryBackoffMs
                    30000L, // maxRetryBackoffMs
                    2.0, // retryBackoffMultiplier
                    null, // stepTimeoutMs
                    processorInfo
            );
            steps.put(stepDef.id(), stepConfig);
        }
        
        return PipelineConfig.builder()
                .name(request.id())
                .pipelineSteps(steps)
                .build();
    }
    
    /**
     * Determine the step type based on its position in the flow.
     */
    private StepType determineStepType(String stepId, Map<String, List<String>> stepConnections, Set<String> hasIncomingConnections) {
        boolean hasInputs = hasIncomingConnections.contains(stepId);
        boolean hasOutputs = !stepConnections.get(stepId).isEmpty();
        
        if (!hasInputs && hasOutputs) {
            return StepType.INITIAL_PIPELINE; // Entry point
        } else if (hasInputs && !hasOutputs) {
            return StepType.SINK; // Terminal step
        } else {
            return StepType.PIPELINE; // Standard step
        }
    }
    
    /**
     * Create output targets for a step based on its 'next' steps.
     * For MVP, using gRPC transport only.
     */
    private Map<String, OutputTarget> createOutputTargets(List<String> nextSteps) {
        if (nextSteps == null || nextSteps.isEmpty()) {
            return Collections.emptyMap();
        }
        
        Map<String, OutputTarget> outputs = new HashMap<>();
        for (int i = 0; i < nextSteps.size(); i++) {
            String targetStep = nextSteps.get(i);
            String outputKey = "output" + (i + 1); // output1, output2, etc.
            
            GrpcTransportConfig grpcConfig = new GrpcTransportConfig(targetStep, Collections.emptyMap());
            OutputTarget outputTarget = new OutputTarget(
                    targetStep,
                    TransportType.GRPC,
                    grpcConfig,
                    null // No Kafka transport for MVP
            );
            outputs.put(outputKey, outputTarget);
        }
        
        return outputs;
    }
    
    /**
     * Convert PipelineConfig to PipelineView.
     */
    public PipelineView toView(String pipelineId, PipelineConfig config, String cluster) {
        return toPipelineView(config, cluster, Map.of());
    }
    
    /**
     * Convert PipelineConfig to PipelineView with metadata.
     */
    public PipelineView toPipelineView(PipelineConfig config, String cluster, Map<String, Object> metadata) {
        // For MVP, create a simple view
        return new PipelineView(
                config.name(),
                metadata.getOrDefault("displayName", config.name()).toString(),
                metadata.getOrDefault("description", "").toString(),
                mapStepsToView(config.pipelineSteps()),
                (List<String>) metadata.getOrDefault("tags", List.of()),
                (Boolean) metadata.getOrDefault("active", true),
                (Instant) metadata.getOrDefault("createdAt", Instant.now()),
                (Instant) metadata.getOrDefault("updatedAt", Instant.now()),
                cluster
        );
    }
    
    /**
     * Convert PipelineConfig to PipelineSummary.
     */
    public PipelineSummary toPipelineSummary(PipelineConfig config, Map<String, Object> metadata) {
        return new PipelineSummary(
                config.name(),
                metadata.getOrDefault("displayName", config.name()).toString(),
                metadata.getOrDefault("description", "").toString(),
                config.pipelineSteps().size(),
                (Boolean) metadata.getOrDefault("active", true),
                (List<String>) metadata.getOrDefault("tags", List.of()),
                (Instant) metadata.getOrDefault("updatedAt", Instant.now())
        );
    }
    
    /**
     * Map pipeline steps to a simple view representation.
     */
    private List<PipelineView.PipelineStepView> mapStepsToView(Map<String, PipelineStepConfig> steps) {
        return steps.entrySet().stream()
                .map(entry -> {
                    var stepConfig = entry.getValue();
                    String moduleId = stepConfig.processorInfo().grpcServiceName() != null 
                            ? stepConfig.processorInfo().grpcServiceName() 
                            : stepConfig.processorInfo().internalProcessorBeanName();
                    return new PipelineView.PipelineStepView(
                            entry.getKey(),
                            moduleId,
                            Map.of(), // Empty config for MVP
                            List.of(), // Empty next steps for MVP
                            "direct", // Default transport
                            null // No kafka topic for direct transport
                    );
                })
                .toList();
    }
}