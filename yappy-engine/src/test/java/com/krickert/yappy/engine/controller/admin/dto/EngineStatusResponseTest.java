package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineStatusResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create a mock health object
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("details", "All systems operational");

        // Create an instance with all fields populated
        EngineStatusResponse original = new EngineStatusResponse(
                "test-cluster",
                false,
                "v1.0.0",
                health
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        EngineStatusResponse deserialized = objectMapper.readValue(json, EngineStatusResponse.class);

        // Verify all fields match
        assertEquals(original.getActiveClusterName(), deserialized.getActiveClusterName());
        assertEquals(original.getIsConfigStale(), deserialized.getIsConfigStale());
        assertEquals(original.getCurrentConfigVersionIdentifier(), deserialized.getCurrentConfigVersionIdentifier());
        
        // Verify the health map
        @SuppressWarnings("unchecked")
        Map<String, Object> deserializedHealth = (Map<String, Object>) deserialized.getMicronautHealth();
        assertEquals("UP", deserializedHealth.get("status"));
        assertEquals("All systems operational", deserializedHealth.get("details"));
    }

    @Test
    void testSerializationWithNullFields() throws Exception {
        // Create an instance with some null fields
        EngineStatusResponse original = new EngineStatusResponse(
                null,
                null,
                null,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        EngineStatusResponse deserialized = objectMapper.readValue(json, EngineStatusResponse.class);

        // Verify all fields match
        assertNull(deserialized.getActiveClusterName());
        assertNull(deserialized.getIsConfigStale());
        assertNull(deserialized.getCurrentConfigVersionIdentifier());
        assertNull(deserialized.getMicronautHealth());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"activeClusterName\":\"test-cluster\"}";

        // Deserialize to object
        EngineStatusResponse deserialized = objectMapper.readValue(json, EngineStatusResponse.class);

        // Verify fields
        assertEquals("test-cluster", deserialized.getActiveClusterName());
        assertNull(deserialized.getIsConfigStale());
        assertNull(deserialized.getCurrentConfigVersionIdentifier());
        assertNull(deserialized.getMicronautHealth());
    }
}