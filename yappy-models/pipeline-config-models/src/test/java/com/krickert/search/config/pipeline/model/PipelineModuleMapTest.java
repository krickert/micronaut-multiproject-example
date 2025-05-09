package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertNotNull(deserialized.getAvailableModules());
        assertEquals(1, deserialized.getAvailableModules().size());
        
        PipelineModuleConfiguration deserializedModule = deserialized.getAvailableModules().get("test-module");
        assertNotNull(deserializedModule);
        assertEquals("Test Module", deserializedModule.getImplementationName());
        assertEquals("test-module", deserializedModule.getImplementationId());
        assertEquals("test-schema", deserializedModule.getCustomConfigSchemaReference().getSubject());
        assertEquals(1, deserializedModule.getCustomConfigSchemaReference().getVersion());
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
        assertNull(deserialized.getAvailableModules());
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
            assertNotNull(moduleMap.getAvailableModules());
            assertEquals(2, moduleMap.getAvailableModules().size());
            
            // Verify first module
            PipelineModuleConfiguration module1 = moduleMap.getAvailableModules().get("test-module-1");
            assertNotNull(module1);
            assertEquals("Test Module 1", module1.getImplementationName());
            assertEquals("test-module-1", module1.getImplementationId());
            assertEquals("test-module-1-schema", module1.getCustomConfigSchemaReference().getSubject());
            assertEquals(1, module1.getCustomConfigSchemaReference().getVersion());
            
            // Verify second module
            PipelineModuleConfiguration module2 = moduleMap.getAvailableModules().get("test-module-2");
            assertNotNull(module2);
            assertEquals("Test Module 2", module2.getImplementationName());
            assertEquals("test-module-2", module2.getImplementationId());
            assertEquals("test-module-2-schema", module2.getCustomConfigSchemaReference().getSubject());
            assertEquals(2, module2.getCustomConfigSchemaReference().getVersion());
        }
    }
}