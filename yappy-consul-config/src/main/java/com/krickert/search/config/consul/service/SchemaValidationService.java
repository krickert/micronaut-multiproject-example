package com.krickert.search.config.consul.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.krickert.search.config.consul.ValidationResult;
import reactor.core.publisher.Mono;

/**
 * Service for validating JSON data against schemas.
 * Provides centralized schema validation for all components in the system.
 */
public interface SchemaValidationService {
    
    /**
     * Validates JSON content against a schema.
     * 
     * @param jsonContent the JSON content to validate (as string)
     * @param schemaContent the JSON schema to validate against (as string)
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validateJson(String jsonContent, String schemaContent);
    
    /**
     * Validates JSON content against a schema.
     * 
     * @param jsonNode the JSON content to validate (as JsonNode)
     * @param schemaNode the JSON schema to validate against (as JsonNode)
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validateJson(JsonNode jsonNode, JsonNode schemaNode);
    
    /**
     * Validates pipeline cluster configuration.
     * 
     * @param clusterConfigJson the cluster configuration JSON
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validatePipelineClusterConfig(String clusterConfigJson);
    
    /**
     * Validates pipeline step configuration.
     * 
     * @param stepConfigJson the step configuration JSON
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validatePipelineStepConfig(String stepConfigJson);
    
    /**
     * Validates custom module configuration against its schema.
     * 
     * @param customConfigJson the custom configuration JSON
     * @param customConfigSchemaJson the schema for the custom configuration
     * @return a Mono containing the validation result
     */
    Mono<ValidationResult> validateCustomModuleConfig(String customConfigJson, String customConfigSchemaJson);
    
    /**
     * Checks if a JSON string is valid JSON syntax.
     * 
     * @param jsonString the JSON string to check
     * @return a Mono containing true if valid JSON, false otherwise
     */
    Mono<Boolean> isValidJson(String jsonString);
    
    /**
     * Gets the default schema for pipeline cluster configuration.
     * 
     * @return a Mono containing the schema as a JSON string
     */
    Mono<String> getPipelineClusterConfigSchema();
    
    /**
     * Gets the default schema for pipeline step configuration.
     * 
     * @return a Mono containing the schema as a JSON string
     */
    Mono<String> getPipelineStepConfigSchema();
}