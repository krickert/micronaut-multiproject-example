package com.krickert.search.pipeline.api.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PipelineMapper.class);
    
    private final ObjectMapper objectMapper;
    
    public PipelineMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        LOG.debug("PipelineMapper initialized with ObjectMapper");
    }
    
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
            
            // For SINK steps, make them internal processors to satisfy validation
            ProcessorInfo processorInfo;
            if (stepType == StepType.SINK) {
                processorInfo = new ProcessorInfo(null, stepDef.module()); // Use as internal processor
            } else {
                processorInfo = new ProcessorInfo(stepDef.module(), null); // Use gRPC service name
            }
            
            // Create output targets for this step
            Map<String, OutputTarget> outputs = createOutputTargets(stepDef.next());
            
            var stepConfig = new PipelineStepConfig(
                    stepDef.id(),
                    stepType,
                    "Test Description for " + stepDef.id(), // description
                    null, // customConfigSchemaId
                    mapToJsonConfigOptions(stepDef.config()), // Convert config to JsonConfigOptions
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
        } else if (!hasInputs && !hasOutputs) {
            // Single-step pipeline: treat as initial pipeline (entry point)
            return StepType.INITIAL_PIPELINE;
        } else {
            return StepType.PIPELINE; // Standard step (has both inputs and outputs)
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
                    
                    // Extract config from JsonConfigOptions
                    LOG.info("mapStepsToView: Processing step {} with customConfig: {}", entry.getKey(), stepConfig.customConfig());
                    Map<String, Object> configMap = extractConfigMap(stepConfig.customConfig());
                    LOG.info("mapStepsToView: Step {} config map: {}", entry.getKey(), configMap);
                    
                    // Extract next steps from outputs
                    List<String> nextSteps = stepConfig.outputs() != null 
                            ? stepConfig.outputs().values().stream()
                                    .map(OutputTarget::targetStepName)
                                    .filter(name -> name != null)
                                    .distinct()
                                    .toList()
                            : List.of();
                    
                    return new PipelineView.PipelineStepView(
                            entry.getKey(),
                            moduleId,
                            configMap,
                            nextSteps,
                            "direct", // Default transport
                            null // No kafka topic for direct transport
                    );
                })
                .toList();
    }
    
    /**
     * Extract config map from JsonConfigOptions.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractConfigMap(PipelineStepConfig.JsonConfigOptions configOptions) {
        if (configOptions == null) {
            LOG.debug("extractConfigMap: configOptions is null");
            return null; // Return null instead of empty map to avoid serialization issues
        }
        
        Map<String, Object> result = new LinkedHashMap<>();
        
        // First add configParams if present
        if (configOptions.configParams() != null && !configOptions.configParams().isEmpty()) {
            LOG.debug("extractConfigMap: adding configParams: {}", configOptions.configParams());
            result.putAll(configOptions.configParams());
        }
        
        // Then add jsonConfig if present (this may override configParams)
        if (configOptions.jsonConfig() != null && !configOptions.jsonConfig().isEmpty()) {
            try {
                Map<String, Object> jsonMap = objectMapper.convertValue(configOptions.jsonConfig(), Map.class);
                if (jsonMap != null && !jsonMap.isEmpty()) {
                    LOG.debug("extractConfigMap: adding jsonConfig: {}", jsonMap);
                    result.putAll(jsonMap);
                }
            } catch (Exception e) {
                LOG.error("extractConfigMap: failed to convert jsonConfig", e);
            }
        }
        
        // Return null if no config found to avoid empty map serialization issues
        if (result.isEmpty()) {
            LOG.debug("extractConfigMap: returning null for empty config");
            return null;
        }
        
        LOG.debug("extractConfigMap: returning result: {}", result);
        return result;
    }
    
    /**
     * Convert a config map to JsonConfigOptions.
     * Uses configParams for simple string values, jsonConfig for complex objects.
     */
    private PipelineStepConfig.JsonConfigOptions mapToJsonConfigOptions(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            // When no config provided, return empty JSON
            return new PipelineStepConfig.JsonConfigOptions(
                    objectMapper.createObjectNode(), Map.of()
            );
        }
        
        // Check if all values are strings - if so, we could use configParams instead
        boolean allStrings = config.values().stream().allMatch(v -> v instanceof String);
        
        if (allStrings) {
            // Simple case: all values are strings, so we can use configParams
            Map<String, String> stringParams = config.entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().toString(),
                            (oldValue, newValue) -> oldValue,
                            LinkedHashMap::new
                    ));
            LOG.info("mapToJsonConfigOptions: Using configParams for simple config: {}", stringParams);
            return new PipelineStepConfig.JsonConfigOptions(
                    objectMapper.createObjectNode(), stringParams
            );
        } else {
            // Complex case: use jsonConfig for nested objects, arrays, etc.
            try {
                var jsonNode = objectMapper.valueToTree(config);
                LOG.info("mapToJsonConfigOptions: Using jsonConfig for complex config: {}", jsonNode);
                return new PipelineStepConfig.JsonConfigOptions(jsonNode, Map.of());
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to convert config to JSON", e);
            }
        }
    }
}