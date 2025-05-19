package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class PipelineModuleConfigurationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a SchemaReference for the test
        SchemaReference schemaReference = new SchemaReference("test-schema", 1);

        // Create a PipelineModuleConfiguration instance
        PipelineModuleConfiguration config = new PipelineModuleConfiguration(
                "Test Module",
                "test-module",
                schemaReference);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineModuleConfiguration deserialized = objectMapper.readValue(json, PipelineModuleConfiguration.class);

        // Verify the values
        assertEquals("Test Module", deserialized.implementationName());
        assertEquals("test-module", deserialized.implementationId());
        assertEquals("test-schema", deserialized.customConfigSchemaReference().subject());
        assertEquals(1, deserialized.customConfigSchemaReference().version());
    }

    @Test
    void testValidation() {
        // Test null implementationName validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineModuleConfiguration(
                null, "test-module", null));

        // Test blank implementationName validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineModuleConfiguration(
                "", "test-module", null));

        // Test null implementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineModuleConfiguration(
                "Test Module", null, null));

        // Test blank implementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineModuleConfiguration(
                "Test Module", "", null));

        // Test that customConfigSchemaReference can be null
        PipelineModuleConfiguration config = new PipelineModuleConfiguration(
                "Test Module", "test-module", null);
        assertNull(config.customConfigSchemaReference());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a SchemaReference for the test
        SchemaReference schemaReference = new SchemaReference("test-schema", 1);

        // Create a PipelineModuleConfiguration instance
        PipelineModuleConfiguration config = new PipelineModuleConfiguration(
                "Test Module",
                "test-module",
                schemaReference);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"implementationName\":\"Test Module\""));
        assertTrue(json.contains("\"implementationId\":\"test-module\""));
        assertTrue(json.contains("\"customConfigSchemaReference\":"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-module-configuration.json")) {
            // Deserialize from JSON
            PipelineModuleConfiguration config = objectMapper.readValue(is, PipelineModuleConfiguration.class);

            // Verify the values
            assertEquals("Test Module", config.implementationName());
            assertEquals("test-module-1", config.implementationId());
            assertEquals("test-module-schema", config.customConfigSchemaReference().subject());
            assertEquals(1, config.customConfigSchemaReference().version());
        }
    }
}
