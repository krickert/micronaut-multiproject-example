package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.config.consul.schema.test.ConsulSchemaRegistrySeeder;
import com.krickert.search.config.pipeline.model.*;
import com.krickert.search.config.schema.model.test.ConsulSchemaRegistryTestHelper;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CustomConfigSchemaValidator.
 * This test verifies that the CustomConfigSchemaValidator correctly ignores the schemaContentProvider
 * parameter and uses the ConsulSchemaRegistryDelegate instead.
 */
@MicronautTest
@Property(name = "consul.client.config.path", value = "config/test-pipeline")
public class CustomConfigSchemaValidatorIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(CustomConfigSchemaValidatorIntegrationTest.class);

    @Inject
    private CustomConfigSchemaValidator validator;

    @Inject
    private ConsulSchemaRegistryDelegate schemaRegistryDelegate;

    @Inject
    private ConsulSchemaRegistrySeeder schemaRegistrySeeder;

    @Inject
    private ObjectMapper objectMapper;

    private static final String TEST_SCHEMA_ID = "test-schema";
    private static final String TEST_SCHEMA_CONTENT = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "TestConfig",
              "type": "object",
              "properties": {
                "name": { "type": "string", "minLength": 3 },
                "value": { "type": "integer", "minimum": 1, "maximum": 100 }
              },
              "required": ["name", "value"]
            }""";

    private static final String VALID_CONFIG = """
            {
              "name": "test",
              "value": 42
            }""";

    private static final String INVALID_CONFIG = """
            {
              "name": "t",
              "value": 0
            }""";

    @BeforeEach
    void setUp() {
        // Register the test schema in Consul
        schemaRegistryDelegate.saveSchema(TEST_SCHEMA_ID, TEST_SCHEMA_CONTENT).block();
    }

    @Test
    void testValidatorIgnoresSchemaContentProviderAndUsesConsulSchemaRegistryDelegate() {
        // Create a schema content provider that would return an invalid schema
        Function<SchemaReference, Optional<String>> invalidSchemaProvider = ref -> {
            // This would cause validation to fail if used
            return Optional.of("{ \"type\": \"invalid\" }");
        };

        // Create a module configuration that references the test schema
        PipelineModuleConfiguration moduleConfig = new PipelineModuleConfiguration(
                "Test Module",
                "test-module",
                new SchemaReference(TEST_SCHEMA_ID, 1)
        );

        // Create a step configuration that uses the module and has a valid custom config
        PipelineStepConfig validStep = new PipelineStepConfig(
                "valid-step",
                StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("test-module", null),
                createJsonConfig(VALID_CONFIG)
        );

        // Create a step configuration that uses the module and has an invalid custom config
        PipelineStepConfig invalidStep = new PipelineStepConfig(
                "invalid-step",
                StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("test-module", null),
                createJsonConfig(INVALID_CONFIG)
        );

        // Create a pipeline configuration with the valid step
        PipelineConfig validPipeline = new PipelineConfig(
                "valid-pipeline",
                Map.of(validStep.stepName(), validStep)
        );

        // Create a pipeline configuration with the invalid step
        PipelineConfig invalidPipeline = new PipelineConfig(
                "invalid-pipeline",
                Map.of(invalidStep.stepName(), invalidStep)
        );

        // Create a pipeline graph configuration with the valid pipeline
        PipelineGraphConfig validGraphConfig = new PipelineGraphConfig(
                Map.of(validPipeline.name(), validPipeline)
        );

        // Create a pipeline graph configuration with the invalid pipeline
        PipelineGraphConfig invalidGraphConfig = new PipelineGraphConfig(
                Map.of(invalidPipeline.name(), invalidPipeline)
        );

        // Create a module map with the test module
        PipelineModuleMap moduleMap = new PipelineModuleMap(
                Map.of(moduleConfig.implementationId(), moduleConfig)
        );

        // Create a cluster configuration with the valid pipeline
        PipelineClusterConfig validClusterConfig = new PipelineClusterConfig(
                "valid-cluster",
                validGraphConfig,
                moduleMap,
                null,
                Collections.emptySet(),
                Collections.emptySet()
        );

        // Create a cluster configuration with the invalid pipeline
        PipelineClusterConfig invalidClusterConfig = new PipelineClusterConfig(
                "invalid-cluster",
                invalidGraphConfig,
                moduleMap,
                null,
                Collections.emptySet(),
                Collections.emptySet()
        );

        // Validate the valid cluster configuration
        List<String> validErrors = validator.validate(validClusterConfig, invalidSchemaProvider);
        assertTrue(validErrors.isEmpty(), "Valid config should not produce errors. Errors: " + validErrors);

        // Validate the invalid cluster configuration
        List<String> invalidErrors = validator.validate(invalidClusterConfig, invalidSchemaProvider);
        assertFalse(invalidErrors.isEmpty(), "Invalid config should produce errors.");
        assertEquals(1, invalidErrors.size(), "Expected one error message grouping schema violations.");
        assertTrue(invalidErrors.get(0).contains("Step 'invalid-step' custom config failed schema validation"));
        
        // Print the actual error message for debugging
        log.info("Actual error message: {}", invalidErrors.get(0));

        // Check for specific validation errors
        assertTrue(invalidErrors.get(0).contains("name"), "Error message should mention 'name'");
        assertTrue(invalidErrors.get(0).contains("value"), "Error message should mention 'value'");
    }

    private PipelineStepConfig.JsonConfigOptions createJsonConfig(String jsonString) {
        try {
            JsonNode node = objectMapper.readTree(jsonString);
            return new PipelineStepConfig.JsonConfigOptions(node, Collections.emptyMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse test JSON string: " + jsonString, e);
        }
    }
}