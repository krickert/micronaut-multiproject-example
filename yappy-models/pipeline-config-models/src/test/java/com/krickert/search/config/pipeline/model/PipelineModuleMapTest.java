package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineModuleMapTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a map of PipelineModuleConfiguration instances
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();

        // Add a module to the map
        SchemaReference schemaReference = new SchemaReference("test-schema", 1);
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", 
                "test-module", 
                schemaReference);
        modules.put("test-module", module);

        // Create a PipelineModuleMap instance
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(moduleMap);

        // Deserialize from JSON
        PipelineModuleMap deserialized = objectMapper.readValue(json, PipelineModuleMap.class);

        // Verify the values
        assertNotNull(deserialized.availableModules());
        assertEquals(1, deserialized.availableModules().size());

        PipelineModuleConfiguration deserializedModule = deserialized.availableModules().get("test-module");
        assertNotNull(deserializedModule);
        assertEquals("Test Module", deserializedModule.implementationName());
        assertEquals("test-module", deserializedModule.implementationId());
        assertEquals("test-schema", deserializedModule.customConfigSchemaReference().subject());
        assertEquals(1, deserializedModule.customConfigSchemaReference().version());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a PipelineModuleMap instance with null values
        PipelineModuleMap moduleMap = new PipelineModuleMap(null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(moduleMap);

        // Deserialize from JSON
        PipelineModuleMap deserialized = objectMapper.readValue(json, PipelineModuleMap.class);

        // Verify the values
        assertTrue(deserialized.availableModules().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a map of PipelineModuleConfiguration instances
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();

        // Add a module to the map
        SchemaReference schemaReference = new SchemaReference("test-schema", 1);
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", 
                "test-module", 
                schemaReference);
        modules.put("test-module", module);

        // Create a PipelineModuleMap instance
        PipelineModuleMap moduleMap = new PipelineModuleMap(modules);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(moduleMap);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"availableModules\":"));
        assertTrue(json.contains("\"test-module\":"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-module-map.json")) {
            // Deserialize from JSON
            PipelineModuleMap moduleMap = objectMapper.readValue(is, PipelineModuleMap.class);

            // Verify the values
            assertNotNull(moduleMap.availableModules());
            assertEquals(2, moduleMap.availableModules().size());

            // Verify first module
            PipelineModuleConfiguration module1 = moduleMap.availableModules().get("test-module-1");
            assertNotNull(module1);
            assertEquals("Test Module 1", module1.implementationName());
            assertEquals("test-module-1", module1.implementationId());
            assertEquals("test-module-1-schema", module1.customConfigSchemaReference().subject());
            assertEquals(1, module1.customConfigSchemaReference().version());

            // Verify second module
            PipelineModuleConfiguration module2 = moduleMap.availableModules().get("test-module-2");
            assertNotNull(module2);
            assertEquals("Test Module 2", module2.implementationName());
            assertEquals("test-module-2", module2.implementationId());
            assertEquals("test-module-2-schema", module2.customConfigSchemaReference().subject());
            assertEquals(2, module2.customConfigSchemaReference().version());
        }
    }
}
