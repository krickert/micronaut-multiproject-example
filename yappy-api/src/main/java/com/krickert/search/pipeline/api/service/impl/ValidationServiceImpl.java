package com.krickert.search.pipeline.api.service.impl;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.ValidationResult;
import com.krickert.search.pipeline.api.service.ValidationService;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implementation of ValidationService with robust checks.
 * Simplified for MVP - real implementation would validate against the complex model.
 */
@Singleton
public class ValidationServiceImpl implements ValidationService {
    
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern KAFKA_TOPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Set<String> RESERVED_NAMES = Set.of(
            "default", "system", "admin", "config", "consul", "kafka", "yappy"
    );
    
    @Override
    public Mono<ValidationResult> validateClusterConfig(PipelineClusterConfig config) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        // Validate cluster name
        if (config.clusterName() == null || config.clusterName().isBlank()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "clusterName", "Cluster name is required", "required"
            ));
        } else if (!CLUSTER_NAME_PATTERN.matcher(config.clusterName()).matches()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "clusterName", "Cluster name must start with lowercase letter and contain only lowercase letters, numbers, and hyphens", "pattern"
            ));
        }
        
        // Validate pipelines
        if (config.pipelineGraphConfig() != null && config.pipelineGraphConfig().pipelines() != null) {
            var pipelines = config.pipelineGraphConfig().pipelines();
            if (pipelines.isEmpty()) {
                messages.add(new ValidationResult.ValidationMessage(
                        "warning", "pipelines", "No pipelines defined in cluster", "empty"
                ));
            }
            
            // Validate each pipeline
            for (var entry : pipelines.entrySet()) {
                validatePipelineConfigInternal(entry.getValue(), "pipelines." + entry.getKey(), messages);
            }
        }
        
        // Validate Kafka topics
        if (config.allowedKafkaTopics() != null) {
            for (String topic : config.allowedKafkaTopics()) {
                if (!KAFKA_TOPIC_PATTERN.matcher(topic).matches()) {
                    messages.add(new ValidationResult.ValidationMessage(
                            "error", "allowedKafkaTopics", "Invalid Kafka topic name: " + topic, "pattern"
                    ));
                }
            }
        }
        
        return Mono.just(new ValidationResult(
                messages.stream().noneMatch(m -> m.severity().equals("error")),
                messages,
                Map.of("clusterName", config.clusterName()),
                "cluster"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validatePipelineConfig(PipelineConfig config) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        validatePipelineConfigInternal(config, "pipeline", messages);
        
        return Mono.just(new ValidationResult(
                messages.stream().noneMatch(m -> m.severity().equals("error")),
                messages,
                Map.of("pipelineName", config.name()),
                "pipeline"
        ));
    }
    
    private void validatePipelineConfigInternal(PipelineConfig config, String path, List<ValidationResult.ValidationMessage> messages) {
        if (config.name() == null || config.name().isBlank()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", path + ".name", "Pipeline name is required", "required"
            ));
        }
        
        if (config.pipelineSteps() == null || config.pipelineSteps().isEmpty()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", path + ".steps", "Pipeline must have at least one step", "required"
            ));
        } else {
            // Simplified validation for MVP
            // Real implementation would validate the complex PipelineStepConfig structure
            for (var entry : config.pipelineSteps().entrySet()) {
                var step = entry.getValue();
                var stepPath = path + ".steps." + entry.getKey();
                
                // Basic validation only - the real model is more complex
                if (step == null) {
                    messages.add(new ValidationResult.ValidationMessage(
                            "error", stepPath, "Step configuration is null", "required"
                    ));
                }
            }
        }
    }
    
    @Override
    public Mono<ValidationResult> validateModuleRegistration(String serviceName, String host, int port) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", "Service name must start with lowercase letter and contain only lowercase letters, numbers, and hyphens", "pattern"
            ));
        }
        
        if (RESERVED_NAMES.contains(serviceName)) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", "Service name '" + serviceName + "' is reserved", "reserved"
            ));
        }
        
        if (host == null || host.isBlank()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "host", "Host is required", "required"
            ));
        }
        
        if (port < 1 || port > 65535) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "port", "Port must be between 1 and 65535", "range"
            ));
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("serviceName", serviceName, "host", host, "port", port),
                "module-registration"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validateKafkaTopic(String topicName) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!KAFKA_TOPIC_PATTERN.matcher(topicName).matches()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "topicName", "Topic name can only contain letters, numbers, dots, underscores, and hyphens", "pattern"
            ));
        }
        
        if (topicName.length() > 249) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "topicName", "Topic name cannot exceed 249 characters", "length"
            ));
        }
        
        if (topicName.equals(".") || topicName.equals("..")) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "topicName", "Topic name cannot be '.' or '..'", "invalid"
            ));
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("topicName", topicName),
                "kafka-topic"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validateJson(String schemaId, String jsonContent) {
        // TODO: Implement JSON schema validation
        return Mono.just(new ValidationResult(
                true,
                List.of(),
                Map.of("schemaId", schemaId),
                "json-schema"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validateServiceName(String serviceName) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!SERVICE_NAME_PATTERN.matcher(serviceName).matches()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", "Service name must start with lowercase letter and contain only lowercase letters, numbers, and hyphens", "pattern"
            ));
        }
        
        if (serviceName.length() > 63) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", "Service name cannot exceed 63 characters", "length"
            ));
        }
        
        if (RESERVED_NAMES.contains(serviceName)) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", "Service name '" + serviceName + "' is reserved", "reserved"
            ));
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("serviceName", serviceName),
                "service-name"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validateClusterName(String clusterName) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!CLUSTER_NAME_PATTERN.matcher(clusterName).matches()) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "clusterName", "Cluster name must start with lowercase letter and contain only lowercase letters, numbers, and hyphens", "pattern"
            ));
        }
        
        if (RESERVED_NAMES.contains(clusterName)) {
            messages.add(new ValidationResult.ValidationMessage(
                    "error", "clusterName", "Cluster name '" + clusterName + "' is reserved", "reserved"
            ));
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("clusterName", clusterName),
                "cluster-name"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validateBeforeOperation(String operationType, Object data) {
        // Route to appropriate validation based on operation type
        return switch (operationType) {
            case "create-pipeline" -> {
                if (data instanceof PipelineConfig config) {
                    yield validatePipelineConfig(config);
                }
                yield Mono.just(new ValidationResult(false, 
                        List.of(new ValidationResult.ValidationMessage("error", "data", "Invalid data type for pipeline creation", "type")),
                        Map.of(), operationType));
            }
            case "create-cluster" -> {
                if (data instanceof PipelineClusterConfig config) {
                    yield validateClusterConfig(config);
                }
                yield Mono.just(new ValidationResult(false,
                        List.of(new ValidationResult.ValidationMessage("error", "data", "Invalid data type for cluster creation", "type")),
                        Map.of(), operationType));
            }
            default -> Mono.just(new ValidationResult(true, List.of(), Map.of(), operationType));
        };
    }
}