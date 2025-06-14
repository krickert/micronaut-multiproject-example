package com.krickert.search.pipeline.api.service;

import com.krickert.search.config.pipeline.model.PipelineClusterConfig;
import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.ValidationResult;
import reactor.core.publisher.Mono;

/**
 * Service interface for robust validation of all configuration data.
 * NEVER allow bad data into the cluster!
 */
public interface ValidationService {
    
    /**
     * Validate a complete cluster configuration.
     * Checks:
     * - Required fields are present
     * - Pipeline IDs are unique
     * - Module references exist
     * - Kafka topics are valid
     * - gRPC services are reachable
     */
    Mono<ValidationResult> validateClusterConfig(PipelineClusterConfig config);
    
    /**
     * Validate a pipeline configuration.
     * Checks:
     * - Pipeline structure is valid (no cycles, all steps reachable)
     * - All referenced modules exist
     * - Step names are unique within pipeline
     * - Transport configurations are valid
     */
    Mono<ValidationResult> validatePipelineConfig(PipelineConfig config);
    
    /**
     * Validate module registration.
     * Checks:
     * - Service is reachable
     * - Health check passes
     * - gRPC service contract is valid
     * - No naming conflicts
     */
    Mono<ValidationResult> validateModuleRegistration(String serviceName, String host, int port);
    
    /**
     * Validate Kafka topic name.
     * Checks:
     * - Follows naming conventions
     * - Topic exists or can be created
     * - Not reserved topic name
     */
    Mono<ValidationResult> validateKafkaTopic(String topicName);
    
    /**
     * Validate JSON against schema.
     */
    Mono<ValidationResult> validateJson(String schemaId, String jsonContent);
    
    /**
     * Validate that a service name follows conventions.
     * Checks:
     * - Only lowercase letters, numbers, hyphens
     * - Starts with letter
     * - Not too long
     * - Not reserved name
     */
    Mono<ValidationResult> validateServiceName(String serviceName);
    
    /**
     * Validate cluster name.
     * Checks:
     * - Follows naming conventions
     * - Not reserved name
     * - Unique
     */
    Mono<ValidationResult> validateClusterName(String clusterName);
    
    /**
     * Comprehensive validation before any create/update operation.
     * This is the main validation entry point that runs all necessary checks.
     */
    Mono<ValidationResult> validateBeforeOperation(String operationType, Object data);
}