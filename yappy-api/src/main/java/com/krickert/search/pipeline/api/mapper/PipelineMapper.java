package com.krickert.search.pipeline.api.mapper;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig;
import com.krickert.search.config.pipeline.model.PipelineStepConfig.ProcessorInfo;
import com.krickert.search.config.pipeline.model.StepType;
import com.krickert.search.pipeline.api.dto.CreatePipelineRequest;
import com.krickert.search.pipeline.api.dto.PipelineView;
import com.krickert.search.pipeline.api.dto.PipelineSummary;
import jakarta.inject.Singleton;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public PipelineConfig toPipelineConfig(CreatePipelineRequest request) {
        // For MVP, we'll create a simple PipelineConfig with basic step mapping
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        
        // Map each step definition to a PipelineStepConfig
        for (var stepDef : request.steps()) {
            // Create a minimal PipelineStepConfig for each step
            var stepConfig = new PipelineStepConfig(
                    stepDef.id(),
                    StepType.PIPELINE, // Default to PIPELINE for MVP
                    new ProcessorInfo(stepDef.module(), null) // Use gRPC service name
            );
            steps.put(stepDef.id(), stepConfig);
        }
        
        return PipelineConfig.builder()
                .name(request.id())
                .pipelineSteps(steps)
                .build();
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