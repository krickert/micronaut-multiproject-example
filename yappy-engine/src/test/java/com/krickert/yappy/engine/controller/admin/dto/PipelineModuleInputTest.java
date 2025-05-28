package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PipelineModuleInputTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        PipelineModuleInput original = new PipelineModuleInput(
                "module-id", 
                "Module Name");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        PipelineModuleInput deserialized = objectMapper.readValue(json, PipelineModuleInput.class);

        // Verify all fields match
        assertEquals(original.getImplementationId(), deserialized.getImplementationId());
        assertEquals(original.getImplementationName(), deserialized.getImplementationName());
    }

    @Test
    void testDeserializationFromJson() throws Exception {
        // JSON representation
        String json = "{\"implementationId\":\"module-id\",\"implementationName\":\"Module Name\"}";

        // Deserialize to object
        PipelineModuleInput deserialized = objectMapper.readValue(json, PipelineModuleInput.class);

        // Verify fields
        assertEquals("module-id", deserialized.getImplementationId());
        assertEquals("Module Name", deserialized.getImplementationName());
    }
}