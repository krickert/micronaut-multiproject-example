package com.krickert.search.config.consul.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.ValidationResult;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest
class JsonV7SchemaValidationServiceTest {
    
    @Inject
    private ObjectMapper objectMapper;
    
    @Inject
    private ResourceResolver resourceResolver;
    
    private JsonV7SchemaValidationService validationService;
    
    @BeforeEach
    void setUp() {
        validationService = new JsonV7SchemaValidationService(objectMapper, resourceResolver);
    }
    
    @Test
    void testValidateJson_ValidJsonAgainstSchema() {
        String validJson = """
            {
                "name": "test",
                "age": 30
            }
            """;
            
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "age": {"type": "integer"}
                },
                "required": ["name", "age"]
            }
            """;
            
        StepVerifier.create(validationService.validateJson(validJson, schema))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateJson_InvalidJsonAgainstSchema() {
        String invalidJson = """
            {
                "name": "test",
                "age": "thirty"
            }
            """;
            
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "age": {"type": "integer"}
                },
                "required": ["name", "age"]
            }
            """;
            
        StepVerifier.create(validationService.validateJson(invalidJson, schema))
                .assertNext(result -> {
                    assertFalse(result.isValid());
                    assertFalse(result.errors().isEmpty());
                    assertTrue(result.errors().stream()
                            .anyMatch(error -> error.contains("integer")));
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateJson_MissingRequiredField() {
        String jsonMissingField = """
            {
                "name": "test"
            }
            """;
            
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "age": {"type": "integer"}
                },
                "required": ["name", "age"]
            }
            """;
            
        StepVerifier.create(validationService.validateJson(jsonMissingField, schema))
                .assertNext(result -> {
                    assertFalse(result.isValid());
                    assertFalse(result.errors().isEmpty());
                    assertTrue(result.errors().stream()
                            .anyMatch(error -> error.contains("required") || error.contains("age")));
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateJson_InvalidJsonSyntax() {
        String invalidJson = "{ invalid json }";
        String schema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object"
            }
            """;
            
        StepVerifier.create(validationService.validateJson(invalidJson, schema))
                .assertNext(result -> {
                    assertFalse(result.isValid());
                    assertEquals(1, result.errors().size());
                    assertTrue(result.errors().get(0).contains("Failed to parse JSON"));
                })
                .verifyComplete();
    }
    
    @Test
    void testIsValidJson_ValidJson() {
        String validJson = """
            {
                "key": "value",
                "number": 123,
                "array": [1, 2, 3]
            }
            """;
            
        StepVerifier.create(validationService.isValidJson(validJson))
                .expectNext(true)
                .verifyComplete();
    }
    
    @Test
    void testIsValidJson_InvalidJson() {
        String invalidJson = "{ invalid: json }";
        
        StepVerifier.create(validationService.isValidJson(invalidJson))
                .expectNext(false)
                .verifyComplete();
    }
    
    @Test
    void testValidatePipelineClusterConfig_ValidConfig() {
        String validClusterConfig = """
            {
                "clusterName": "test-cluster",
                "pipelineGraphConfig": {
                    "pipelines": {
                        "pipeline1": {
                            "name": "Test Pipeline",
                            "pipelineSteps": {}
                        }
                    }
                }
            }
            """;
            
        StepVerifier.create(validationService.validatePipelineClusterConfig(validClusterConfig))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
    
    @Test
    void testValidatePipelineClusterConfig_MissingClusterName() {
        String invalidClusterConfig = """
            {
                "pipelineGraphConfig": {
                    "pipelines": {}
                }
            }
            """;
            
        StepVerifier.create(validationService.validatePipelineClusterConfig(invalidClusterConfig))
                .assertNext(result -> {
                    assertFalse(result.isValid());
                    assertFalse(result.errors().isEmpty());
                    assertTrue(result.errors().stream()
                            .anyMatch(error -> error.contains("required") || error.contains("clusterName")));
                })
                .verifyComplete();
    }
    
    @Test
    void testValidatePipelineStepConfig_ValidConfig() {
        String validStepConfig = """
            {
                "stepName": "test-step",
                "stepType": "PIPELINE",
                "processorInfo": {
                    "grpcServiceName": "test-service"
                }
            }
            """;
            
        StepVerifier.create(validationService.validatePipelineStepConfig(validStepConfig))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
    
    @Test
    void testValidatePipelineStepConfig_InvalidStepType() {
        String invalidStepConfig = """
            {
                "stepName": "test-step",
                "stepType": "INVALID_TYPE",
                "processorInfo": {}
            }
            """;
            
        StepVerifier.create(validationService.validatePipelineStepConfig(invalidStepConfig))
                .assertNext(result -> {
                    assertFalse(result.isValid());
                    assertFalse(result.errors().isEmpty());
                    assertTrue(result.errors().stream()
                            .anyMatch(error -> error.contains("enum") || error.contains("stepType")));
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateCustomModuleConfig_NoSchemaProvided() {
        String customConfig = """
            {
                "someConfig": "value"
            }
            """;
            
        StepVerifier.create(validationService.validateCustomModuleConfig(customConfig, null))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateCustomModuleConfig_WithSchemaValidation() {
        String customConfig = """
            {
                "port": 8080,
                "host": "localhost"
            }
            """;
            
        String customSchema = """
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "port": {"type": "integer", "minimum": 1, "maximum": 65535},
                    "host": {"type": "string"}
                },
                "required": ["port", "host"]
            }
            """;
            
        StepVerifier.create(validationService.validateCustomModuleConfig(customConfig, customSchema))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
    
    @Test
    void testValidateJson_WithJsonNodes() throws Exception {
        JsonNode jsonNode = objectMapper.readTree("""
            {
                "value": 42
            }
            """);
            
        JsonNode schemaNode = objectMapper.readTree("""
            {
                "$schema": "http://json-schema.org/draft-07/schema#",
                "type": "object",
                "properties": {
                    "value": {"type": "integer"}
                }
            }
            """);
            
        StepVerifier.create(validationService.validateJson(jsonNode, schemaNode))
                .assertNext(result -> {
                    assertTrue(result.isValid());
                    assertTrue(result.errors().isEmpty());
                })
                .verifyComplete();
    }
}