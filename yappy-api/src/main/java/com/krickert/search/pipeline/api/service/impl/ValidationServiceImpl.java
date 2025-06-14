package com.krickert.search.pipeline.api.service.impl;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.config.pipeline.validation.PipelineStructuralValidator;
import com.krickert.search.config.pipeline.validation.StructuralValidationResult;
import com.krickert.search.pipeline.api.dto.ValidationResult;
import com.krickert.search.pipeline.api.service.ValidationService;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Implementation of ValidationService with robust checks.
 * Provides client-side structural validation before server-side comprehensive validation.
 * This includes basic structural checks and simple loop detection.
 */
@Singleton
public class ValidationServiceImpl implements ValidationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(ValidationServiceImpl.class);
    
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern KAFKA_TOPIC_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Pattern PIPELINE_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*$");
    private static final Set<String> RESERVED_NAMES = Set.of(
            "default", "system", "admin", "config", "consul", "kafka", "yappy"
    );
    
    @Override
    public Mono<ValidationResult> validateClusterConfig(PipelineClusterConfig config) {
        // Use the structural validator from commons
        StructuralValidationResult structuralResult = PipelineStructuralValidator.validateCluster(config);
        
        // Convert structural validation result to API ValidationResult
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!structuralResult.valid()) {
            for (String error : structuralResult.errors()) {
                // Parse the error to extract field info if possible
                String field = "cluster";
                String rule = "structural";
                
                // Try to extract field from error message patterns
                if (error.contains("clusterName")) {
                    field = "clusterName";
                } else if (error.contains("Pipeline '")) {
                    int start = error.indexOf("Pipeline '") + 10;
                    int end = error.indexOf("'", start);
                    if (end > start) {
                        field = "pipelines." + error.substring(start, end);
                    }
                }
                
                messages.add(new ValidationResult.ValidationMessage(
                    "error", field, error, rule
                ));
            }
        }
        
        return Mono.just(new ValidationResult(
                messages.stream().noneMatch(m -> m.severity().equals("error")),
                messages,
                Map.of("clusterName", config != null && config.clusterName() != null ? config.clusterName() : "unknown"),
                "cluster"
        ));
    }
    
    @Override
    public Mono<ValidationResult> validatePipelineConfig(PipelineConfig config) {
        // For single pipeline validation, we need a pipeline ID
        // Use the pipeline name as ID if available
        String pipelineId = config != null && config.name() != null ? config.name() : "unknown";
        
        // Use the structural validator from commons
        StructuralValidationResult structuralResult = PipelineStructuralValidator.validatePipeline(pipelineId, config);
        
        // Convert structural validation result to API ValidationResult
        List<ValidationResult.ValidationMessage> messages = convertStructuralErrors(structuralResult.errors(), "pipeline");
        
        return Mono.just(new ValidationResult(
                messages.stream().noneMatch(m -> m.severity().equals("error")),
                messages,
                Map.of("pipelineName", config != null && config.name() != null ? config.name() : "unknown"),
                "pipeline"
        ));
    }
    
    private List<ValidationResult.ValidationMessage> convertStructuralErrors(List<String> errors, String baseField) {
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        for (String error : errors) {
            String field = baseField;
            String rule = "structural";
            
            // Extract rule name if present
            if (error.startsWith("[") && error.contains("]")) {
                int end = error.indexOf("]");
                rule = error.substring(1, end);
                error = error.substring(end + 2); // Skip "] "
            }
            
            // Try to extract more specific field info
            if (error.contains("Step '") && error.contains("'")) {
                int start = error.indexOf("Step '") + 6;
                int end = error.indexOf("'", start);
                if (end > start) {
                    field = baseField + ".steps." + error.substring(start, end);
                }
            } else if (error.contains("step ID '") && error.contains("'")) {
                int start = error.indexOf("step ID '") + 9;
                int end = error.indexOf("'", start);
                if (end > start) {
                    field = baseField + ".steps." + error.substring(start, end);
                }
            }
            
            messages.add(new ValidationResult.ValidationMessage("error", field, error, rule));
        }
        
        return messages;
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
        // Use the structural validator from commons
        StructuralValidationResult structuralResult = PipelineStructuralValidator.validateKafkaTopic(topicName);
        
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!structuralResult.valid()) {
            for (String error : structuralResult.errors()) {
                messages.add(new ValidationResult.ValidationMessage(
                    "error", "topicName", error, "structural"
                ));
            }
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("topicName", topicName != null ? topicName : ""),
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
        // Use the structural validator from commons
        StructuralValidationResult structuralResult = PipelineStructuralValidator.validateServiceName(serviceName);
        
        List<ValidationResult.ValidationMessage> messages = new ArrayList<>();
        
        if (!structuralResult.valid()) {
            for (String error : structuralResult.errors()) {
                messages.add(new ValidationResult.ValidationMessage(
                    "error", "serviceName", error, "structural"
                ));
            }
        }
        
        // Add reserved name check (not in structural validator)
        if (serviceName != null && RESERVED_NAMES.contains(serviceName)) {
            messages.add(new ValidationResult.ValidationMessage(
                "error", "serviceName", "Service name '" + serviceName + "' is reserved", "reserved"
            ));
        }
        
        return Mono.just(new ValidationResult(
                messages.isEmpty(),
                messages,
                Map.of("serviceName", serviceName != null ? serviceName : ""),
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