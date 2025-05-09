package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("Test Module", deserialized.getImplementationName());
        assertEquals("test-module", deserialized.getImplementationId());
        assertEquals("test-schema", deserialized.getCustomConfigSchemaReference().getSubject());
        assertEquals(1, deserialized.getCustomConfigSchemaReference().getVersion());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a PipelineModuleConfiguration instance with null values
        PipelineModuleConfiguration config = new PipelineModuleConfiguration(null, null, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineModuleConfiguration deserialized = objectMapper.readValue(json, PipelineModuleConfiguration.class);

        // Verify the values
        assertNull(deserialized.getImplementationName());
        assertNull(deserialized.getImplementationId());
        assertNull(deserialized.getCustomConfigSchemaReference());
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
            assertEquals("Test Module", config.getImplementationName());
            assertEquals("test-module-1", config.getImplementationId());
            assertEquals("test-module-schema", config.getCustomConfigSchemaReference().getSubject());
            assertEquals(1, config.getCustomConfigSchemaReference().getVersion());
        }
    }
}