package com.krickert.search.config.consul.validator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.schema.delegate.ConsulSchemaRegistryDelegate;
// ConsulSchemaRegistrySeeder is not strictly necessary if we use delegate.saveSchema
// import com.krickert.search.config.consul.schema.test.ConsulSchemaRegistrySeeder;
import com.krickert.search.config.pipeline.model.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CustomConfigSchemaValidator.
 * This test verifies that the CustomConfigSchemaValidator correctly uses the
 * schemaContentProvider parameter.
 */
@MicronautTest
@Property(name = "consul.client.config.path", value = "config/test-pipeline-customvalidator") // Unique path
public class CustomConfigSchemaValidatorIntegrationTest {
    private static final Logger log = LoggerFactory.getLogger(CustomConfigSchemaValidatorIntegrationTest.class);
    private static final String TEST_SCHEMA_SUBJECT = "test-schema-for-validator"; // Changed from ID to subject
    private static final int TEST_SCHEMA_VERSION = 1;
    private static final SchemaReference TEST_SCHEMA_REF = new SchemaReference(TEST_SCHEMA_SUBJECT, TEST_SCHEMA_VERSION);

    private static final String WELL_FORMED_SCHEMA_CONTENT = """
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

    private static final String MALFORMED_SCHEMA_CONTENT = "{ \"type\": \"invalid-schema-type\" }";

    private static final String VALID_DATA_FOR_WELL_FORMED_SCHEMA = """
            {
              "name": "valid-name",
              "value": 50
            }""";
    private static final String INVALID_DATA_FOR_WELL_FORMED_SCHEMA = """
            {
              "name": "t",
              "value": 200
            }""";

    @Inject
    private CustomConfigSchemaValidator validator;
    @Inject
    private ConsulSchemaRegistryDelegate schemaRegistryDelegate;
    @Inject
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // Seed a well-formed schema into Consul. This is mostly to ensure Consul is up
        // and the delegate works, but the tests below will primarily focus on the provider.
        // Note: CustomConfigSchemaValidator, when using the provider, won't directly hit this
        // unless the provider itself is designed to fall back to it (which it isn't by default).
        schemaRegistryDelegate.saveSchema(TEST_SCHEMA_SUBJECT, WELL_FORMED_SCHEMA_CONTENT).block();
        log.info("Seeded schema '{}' into Consul for baseline.", TEST_SCHEMA_SUBJECT);
    }

    private PipelineClusterConfig createTestClusterConfig(String stepName, String configData) {
        PipelineModuleConfiguration moduleConfig = new PipelineModuleConfiguration(
                "Test Module", "test-module", TEST_SCHEMA_REF);
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(moduleConfig.implementationId(), moduleConfig));

        PipelineStepConfig step = new PipelineStepConfig(
                stepName, StepType.PIPELINE,
                new PipelineStepConfig.ProcessorInfo("test-module", null),
                createJsonConfig(configData));
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));

        return new PipelineClusterConfig("test-cluster", graphConfig, moduleMap, null, Collections.emptySet(), Collections.emptySet());
    }

    @Test
    @DisplayName("Should pass validation when provider supplies a valid schema and data is valid")
    void testWithValidSchemaAndValidDataFromProvider() {
        Function<SchemaReference, Optional<String>> validSchemaProvider = ref -> {
            if (TEST_SCHEMA_REF.equals(ref)) {
                return Optional.of(WELL_FORMED_SCHEMA_CONTENT);
            }
            return Optional.empty();
        };
        PipelineClusterConfig clusterConfig = createTestClusterConfig("step-valid-data", VALID_DATA_FOR_WELL_FORMED_SCHEMA);

        List<String> errors = validator.validate(clusterConfig, validSchemaProvider);
        assertTrue(errors.isEmpty(), "Validation should pass with valid schema and valid data from provider. Errors: " + errors);
    }

    @Test
    @DisplayName("Should fail validation when provider supplies a valid schema but data is invalid")
    void testWithValidSchemaAndInvalidDataFromProvider() {
        Function<SchemaReference, Optional<String>> validSchemaProvider = ref -> {
            if (TEST_SCHEMA_REF.equals(ref)) {
                return Optional.of(WELL_FORMED_SCHEMA_CONTENT);
            }
            return Optional.empty();
        };
        PipelineClusterConfig clusterConfig = createTestClusterConfig("step-invalid-data", INVALID_DATA_FOR_WELL_FORMED_SCHEMA);

        List<String> errors = validator.validate(clusterConfig, validSchemaProvider);
        assertFalse(errors.isEmpty(), "Validation should fail with valid schema and invalid data from provider.");
        assertEquals(1, errors.size(), "Expected one error message grouping schema violations.");
        String errorMessage = errors.get(0);
        log.info("Validation error for invalid data with valid schema: {}", errorMessage);
        assertTrue(errorMessage.contains("Step 'step-invalid-data' custom config failed schema validation"));
        assertTrue(errorMessage.contains("name: must be at least 3 characters long")); // Corrected based on typical networknt message
        assertTrue(errorMessage.contains("value: must have a maximum value of 100")); // CORRECTED to match networknt
    }

    @Test
    @DisplayName("Should fail validation when provider supplies a malformed schema")
    void testWithMalformedSchemaFromProvider() {
        Function<SchemaReference, Optional<String>> malformedSchemaProvider = ref -> {
            if (TEST_SCHEMA_REF.equals(ref)) {
                return Optional.of(MALFORMED_SCHEMA_CONTENT);
            }
            return Optional.empty();
        };
        // Data doesn't matter as much as the schema being malformed
        PipelineClusterConfig clusterConfig = createTestClusterConfig("step-any-data", VALID_DATA_FOR_WELL_FORMED_SCHEMA);

        List<String> errors = validator.validate(clusterConfig, malformedSchemaProvider);
        assertFalse(errors.isEmpty(), "Validation should fail if provider supplies a malformed schema.");
        assertEquals(1, errors.size());
        String errorMessage = errors.get(0);
        log.info("Validation error with malformed schema: {}", errorMessage);
        // Adjust to the actual error message observed
        assertTrue(errorMessage.contains("$: object found, unknown expected") || // Actual error from log
                        errorMessage.contains("Error during JSON schema validation"), // Fallback if parsing itself fails catastrophically
                "Error message should indicate a problem with schema processing. Actual: " + errorMessage);
    }

    @Test
    @DisplayName("Should fail validation when provider returns empty for a required schema")
    void testWithEmptySchemaFromProvider() {
        Function<SchemaReference, Optional<String>> emptySchemaProvider = ref -> Optional.empty();
        PipelineClusterConfig clusterConfig = createTestClusterConfig("step-needs-schema", VALID_DATA_FOR_WELL_FORMED_SCHEMA);

        List<String> errors = validator.validate(clusterConfig, emptySchemaProvider);
        assertFalse(errors.isEmpty(), "Validation should fail if provider returns empty for a required schema.");
        assertEquals(1, errors.size());
        String errorMessage = errors.get(0);
        log.info("Validation error with empty schema from provider: {}", errorMessage);
        assertTrue(errorMessage.contains("Schema content for module-defined schemaRef '" + TEST_SCHEMA_REF.toIdentifier() + "' (referenced by step 'step-needs-schema') not found via provider"));
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