package com.krickert.search.config.consul.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.ValidationResult;
import com.krickert.search.config.consul.service.SchemaValidationService;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.micronaut.core.io.ResourceResolver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of SchemaValidationService using NetworkNT JSON Schema validator (v7).
 */
@Singleton
public class JsonV7SchemaValidationService implements SchemaValidationService {
    
    private static final Logger LOG = LoggerFactory.getLogger(JsonV7SchemaValidationService.class);
    
    private final ObjectMapper objectMapper;
    private final ResourceResolver resourceResolver;
    private final JsonSchemaFactory schemaFactory;
    
    // Schema file paths
    private static final String PIPELINE_CLUSTER_CONFIG_SCHEMA = "classpath:schemas/pipeline-cluster-config-schema.json";
    private static final String PIPELINE_STEP_CONFIG_SCHEMA = "classpath:schemas/pipeline-step-config-schema.json";
    
    @Inject
    public JsonV7SchemaValidationService(ObjectMapper objectMapper, ResourceResolver resourceResolver) {
        this.objectMapper = objectMapper;
        this.resourceResolver = resourceResolver;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }
    
    @Override
    public Mono<ValidationResult> validateJson(String jsonContent, String schemaContent) {
        return Mono.fromCallable(() -> {
            try {
                JsonNode jsonNode = objectMapper.readTree(jsonContent);
                JsonNode schemaNode = objectMapper.readTree(schemaContent);
                return validateJson(jsonNode, schemaNode).block();
            } catch (IOException e) {
                LOG.error("Failed to parse JSON or schema: {}", e.getMessage());
                return ValidationResult.invalid("Failed to parse JSON or schema: " + e.getMessage());
            }
        });
    }
    
    @Override
    public Mono<ValidationResult> validateJson(JsonNode jsonNode, JsonNode schemaNode) {
        return Mono.fromCallable(() -> {
            try {
                JsonSchema schema = schemaFactory.getSchema(schemaNode);
                Set<ValidationMessage> errors = schema.validate(jsonNode);
                
                if (errors.isEmpty()) {
                    return ValidationResult.valid();
                } else {
                    List<String> errorMessages = errors.stream()
                            .map(ValidationMessage::getMessage)
                            .collect(Collectors.toList());
                    
                    LOG.debug("Validation errors: {}", errorMessages);
                    
                    return ValidationResult.invalid(errorMessages);
                }
            } catch (Exception e) {
                LOG.error("Error during validation: {}", e.getMessage(), e);
                return ValidationResult.invalid("Validation error: " + e.getMessage());
            }
        });
    }
    
    @Override
    public Mono<ValidationResult> validatePipelineClusterConfig(String clusterConfigJson) {
        return getPipelineClusterConfigSchema()
                .flatMap(schema -> validateJson(clusterConfigJson, schema));
    }
    
    @Override
    public Mono<ValidationResult> validatePipelineStepConfig(String stepConfigJson) {
        return getPipelineStepConfigSchema()
                .flatMap(schema -> validateJson(stepConfigJson, schema));
    }
    
    @Override
    public Mono<ValidationResult> validateCustomModuleConfig(String customConfigJson, String customConfigSchemaJson) {
        if (customConfigSchemaJson == null || customConfigSchemaJson.trim().isEmpty()) {
            // If no schema provided, just validate it's valid JSON
            return isValidJson(customConfigJson)
                    .map(valid -> valid ? ValidationResult.valid() : ValidationResult.invalid("Invalid JSON syntax"));
        }
        
        return validateJson(customConfigJson, customConfigSchemaJson);
    }
    
    @Override
    public Mono<Boolean> isValidJson(String jsonString) {
        return Mono.fromCallable(() -> {
            try {
                objectMapper.readTree(jsonString);
                return true;
            } catch (IOException e) {
                return false;
            }
        });
    }
    
    @Override
    public Mono<String> getPipelineClusterConfigSchema() {
        return loadSchemaFromResource(PIPELINE_CLUSTER_CONFIG_SCHEMA)
                .switchIfEmpty(Mono.fromCallable(this::getDefaultPipelineClusterConfigSchema));
    }
    
    @Override
    public Mono<String> getPipelineStepConfigSchema() {
        return loadSchemaFromResource(PIPELINE_STEP_CONFIG_SCHEMA)
                .switchIfEmpty(Mono.fromCallable(this::getDefaultPipelineStepConfigSchema));
    }
    
    private Mono<String> loadSchemaFromResource(String resourcePath) {
        return Mono.fromCallable(() -> {
            Optional<URL> resourceUrl = resourceResolver.getResource(resourcePath);
            if (resourceUrl.isPresent()) {
                try (InputStream is = resourceUrl.get().openStream()) {
                    return new String(is.readAllBytes());
                }
            }
            return null;
        }).onErrorResume(e -> {
            LOG.warn("Failed to load schema from {}: {}", resourcePath, e.getMessage());
            return Mono.empty();
        });
    }
    
    /**
     * Default schema for pipeline cluster configuration.
     * This is used when the schema file is not found in resources.
     */
    private String getDefaultPipelineClusterConfigSchema() {
        return """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["clusterName"],
              "properties": {
                "clusterName": {
                  "type": "string",
                  "minLength": 1
                },
                "pipelineGraphConfig": {
                  "type": "object",
                  "properties": {
                    "pipelines": {
                      "type": "object",
                      "additionalProperties": {
                        "$ref": "#/definitions/pipelineConfig"
                      }
                    }
                  }
                },
                "pipelineModuleMap": {
                  "type": "object",
                  "properties": {
                    "availableModules": {
                      "type": "object",
                      "additionalProperties": {
                        "$ref": "#/definitions/moduleConfig"
                      }
                    }
                  }
                },
                "defaultPipelineName": {
                  "type": "string"
                },
                "allowedKafkaTopics": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                },
                "allowedGrpcServices": {
                  "type": "array",
                  "items": {
                    "type": "string"
                  }
                }
              },
              "definitions": {
                "pipelineConfig": {
                  "type": "object",
                  "required": ["name", "pipelineSteps"],
                  "properties": {
                    "name": {
                      "type": "string",
                      "minLength": 1
                    },
                    "pipelineSteps": {
                      "type": "object"
                    }
                  }
                },
                "moduleConfig": {
                  "type": "object",
                  "required": ["implementationName", "implementationId"],
                  "properties": {
                    "implementationName": {
                      "type": "string",
                      "minLength": 1
                    },
                    "implementationId": {
                      "type": "string",
                      "minLength": 1
                    },
                    "customConfigSchemaReference": {
                      "type": "object"
                    },
                    "customConfig": {
                      "type": "object"
                    }
                  }
                }
              }
            }
            """;
    }
    
    /**
     * Default schema for pipeline step configuration.
     * This is used when the schema file is not found in resources.
     */
    private String getDefaultPipelineStepConfigSchema() {
        return """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "required": ["stepName", "stepType", "processorInfo"],
              "properties": {
                "stepName": {
                  "type": "string",
                  "minLength": 1
                },
                "stepType": {
                  "type": "string",
                  "enum": ["PIPELINE", "INITIAL_PIPELINE", "SINK"]
                },
                "description": {
                  "type": "string"
                },
                "customConfigSchemaId": {
                  "type": "string"
                },
                "customConfig": {
                  "type": "object",
                  "properties": {
                    "jsonConfig": {
                      "type": "object"
                    },
                    "configParams": {
                      "type": "object"
                    }
                  }
                },
                "kafkaInputs": {
                  "type": "array",
                  "items": {
                    "type": "object"
                  }
                },
                "outputs": {
                  "type": "object"
                },
                "processorInfo": {
                  "type": "object",
                  "properties": {
                    "grpcServiceName": {
                      "type": "string"
                    },
                    "internalProcessorBeanName": {
                      "type": "string"
                    }
                  }
                }
              }
            }
            """;
    }
}