package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the CustomConfigSchemaValidator class.
 * <br/>
 * These tests verify that the validator correctly:
 * 1. Validates custom configurations against their JSON schemas
 * 2. Handles missing schemas
 * 3. Handles malformed JSON
 * 4. Handles valid configurations
 */
class CustomConfigSchemaValidatorTest {

    private CustomConfigSchemaValidator validator;
    private Function<SchemaReference, Optional<String>> schemaContentProvider;
    private Map<SchemaReference, String> schemaMap;

    // Test JSON schemas
    private static final String VALID_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": { "type": "string" },
                "age": { "type": "integer", "minimum": 0 },
                "email": { "type": "string", "format": "email" }
              },
              "required": ["name", "age"]
            }""";

    private static final String MALFORMED_SCHEMA = "{ this is not valid JSON }";

    // Test JSON configs
    private static final String VALID_CONFIG = """
            {
              "name": "John Doe",
              "age": 30,
              "email": "john.doe@example.com"
            }""";

    private static final String INVALID_CONFIG_MISSING_REQUIRED = """
            {
              "name": "John Doe"
            }""";

    private static final String INVALID_CONFIG_WRONG_TYPE = """
            {
              "name": "John Doe",
              "age": "thirty",
              "email": "john.doe@example.com"
            }""";

    private static final String MALFORMED_CONFIG = "{ this is not valid JSON }";

    @BeforeEach
    void setUp() {
        validator = new CustomConfigSchemaValidator(new ObjectMapper());
        schemaMap = new HashMap<>();

        // Set up schema content provider to return schemas from the map
        schemaContentProvider = ref -> Optional.ofNullable(schemaMap.get(ref));

        // Add valid schema to the map
        SchemaReference validSchemaRef = new SchemaReference("test-schema", 1);
        schemaMap.put(validSchemaRef, VALID_SCHEMA);

        // Add malformed schema to the map
        SchemaReference malformedSchemaRef = new SchemaReference("malformed-schema", 1);
        schemaMap.put(malformedSchemaRef, MALFORMED_SCHEMA);
    }

    @Test
    void validate_validConfig_returnsNoErrors() {
        // Create a valid pipeline configuration with a valid custom config
        PipelineClusterConfig clusterConfig = createTestClusterConfig("test-schema", VALID_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Valid configuration should not produce any errors");
    }

    @Test
    void validate_invalidConfigMissingRequired_returnsErrors() {
        // Create a pipeline configuration with an invalid custom config (missing required field)
        PipelineClusterConfig clusterConfig = createTestClusterConfig("test-schema", INVALID_CONFIG_MISSING_REQUIRED);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Invalid configuration should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("failed schema validation")), 
                "Error should indicate schema validation failure");
        assertTrue(errors.stream().anyMatch(e -> e.contains("age")), 
                "Error should mention the missing required field");
    }

    @Test
    void validate_invalidConfigWrongType_returnsErrors() {
        // Create a pipeline configuration with an invalid custom config (wrong type)
        PipelineClusterConfig clusterConfig = createTestClusterConfig("test-schema", INVALID_CONFIG_WRONG_TYPE);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Invalid configuration should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("failed schema validation")), 
                "Error should indicate schema validation failure");
        assertTrue(errors.stream().anyMatch(e -> e.contains("age")), 
                "Error should mention the field with the wrong type");
    }

    @Test
    void validate_malformedConfig_returnsErrors() {
        // Create a pipeline configuration with a malformed custom config
        PipelineClusterConfig clusterConfig = createTestClusterConfig("test-schema", MALFORMED_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Malformed configuration should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Error validating custom config")), 
                "Error should indicate validation error");
    }

    @Test
    void validate_malformedSchema_returnsErrors() {
        // Create a pipeline configuration with a valid custom config but referencing a malformed schema
        PipelineClusterConfig clusterConfig = createTestClusterConfig("malformed-schema", VALID_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Malformed schema should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Error validating custom config")), 
                "Error should indicate validation error");
    }

    @Test
    void validate_missingSchema_returnsErrors() {
        // Create a pipeline configuration with a valid custom config but referencing a non-existent schema
        PipelineClusterConfig clusterConfig = createTestClusterConfig("non-existent-schema", VALID_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Missing schema should produce errors");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Schema content for")), 
                "Error should indicate missing schema");
        assertTrue(errors.stream().anyMatch(e -> e.contains("not found by provider")), 
                "Error should indicate schema not found by provider");
    }

    @Test
    void validate_nullClusterConfig_returnsNoErrors() {
        // The CustomConfigSchemaValidator now checks for null clusterConfig and returns an empty list
        // This behavior is different from ReferentialIntegrityValidator which returns an error message

        List<String> errors = validator.validate(null, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Null cluster config should not produce errors");
    }

    @Test
    void validate_noCustomConfig_returnsNoErrors() {
        // Create a pipeline configuration with no custom config
        PipelineClusterConfig clusterConfig = createTestClusterConfigWithoutCustomConfig("test-schema");

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Configuration without custom config should not produce errors");
    }

    @Test
    void validate_noSchemaReference_returnsNoErrors() {
        // Create a pipeline configuration with a custom config but no schema reference
        PipelineClusterConfig clusterConfig = createTestClusterConfigWithoutSchemaRef(VALID_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertTrue(errors.isEmpty(), "Configuration without schema reference should not produce errors");
    }

    @Test
    void validate_caseSensitiveSchemaSubject_returnsErrors() {
        // Add a schema with a lowercase subject to the map
        SchemaReference lowercaseSchemaRef = new SchemaReference("test-schema", 1);
        schemaMap.put(lowercaseSchemaRef, VALID_SCHEMA);

        // Create a pipeline configuration with a valid custom config but referencing a schema with a different case
        PipelineClusterConfig clusterConfig = createTestClusterConfig("TEST-SCHEMA", VALID_CONFIG);

        List<String> errors = validator.validate(clusterConfig, schemaContentProvider);

        assertFalse(errors.isEmpty(), "Schema with different case should be treated as different");
        assertTrue(errors.stream().anyMatch(e -> e.contains("Schema content for")), 
                "Error should indicate missing schema");
        assertTrue(errors.stream().anyMatch(e -> e.contains("not found by provider")), 
                "Error should indicate schema not found by provider");
    }

    /**
     * Helper method to create a test cluster configuration with a custom config and schema reference.
     */
    private PipelineClusterConfig createTestClusterConfig(String schemaSubject, String jsonConfig) {
        // Create a module that will be referenced by the step
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
            "Test Module", "test-module", new SchemaReference(schemaSubject, 1)
        );
        modules.put("test-module", module);

        // Create a step with a custom config
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
            "step1", "test-module", new JsonConfigOptions(jsonConfig), null, null, null
        );
        steps.put("step1", step);

        // Create a pipeline with the step
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        // Create a cluster config with the pipeline and module
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        return new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );
    }

    /**
     * Helper method to create a test cluster configuration without a custom config.
     */
    private PipelineClusterConfig createTestClusterConfigWithoutCustomConfig(@SuppressWarnings("SameParameterValue") String schemaSubject) {
        // Create a module that will be referenced by the step
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
            "Test Module", "test-module", new SchemaReference(schemaSubject, 1)
        );
        modules.put("test-module", module);

        // Create a step without a custom config
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
            "step1", "test-module", null, null, null, null
        );
        steps.put("step1", step);

        // Create a pipeline with the step
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        // Create a cluster config with the pipeline and module
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        return new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );
    }

    /**
     * Helper method to create a test cluster configuration without a schema reference.
     */
    private PipelineClusterConfig createTestClusterConfigWithoutSchemaRef(@SuppressWarnings("SameParameterValue") String jsonConfig) {
        // Create a module that will be referenced by the step, but without a schema reference
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
            "Test Module", "test-module", null
        );
        modules.put("test-module", module);

        // Create a step with a custom config
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
            "step1", "test-module", new JsonConfigOptions(jsonConfig), null, null, null
        );
        steps.put("step1", step);

        // Create a pipeline with the step
        PipelineConfig pipeline = new PipelineConfig("pipeline1", steps);
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("pipeline1", pipeline);

        // Create a cluster config with the pipeline and module
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(pipelines);
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        return new PipelineClusterConfig(
            "test-cluster", graphConfig, moduleMap, null, null
        );
    }
}
