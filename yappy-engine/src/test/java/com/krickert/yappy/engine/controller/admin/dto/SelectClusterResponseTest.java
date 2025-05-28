package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SelectClusterResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        SelectClusterResponse original = new SelectClusterResponse(
                true,
                "Cluster selected successfully"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SelectClusterResponse deserialized = objectMapper.readValue(json, SelectClusterResponse.class);

        // Verify all fields match
        assertTrue(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
    }

    @Test
    void testSerializationWithFailureResponse() throws Exception {
        // Create an instance with failure response
        SelectClusterResponse original = new SelectClusterResponse(
                false,
                "Cluster selection failed: Cluster not found"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SelectClusterResponse deserialized = objectMapper.readValue(json, SelectClusterResponse.class);

        // Verify all fields match
        assertFalse(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
    }

    @Test
    void testDeserializationFromJson() throws Exception {
        // JSON representation
        String json = "{\"success\":true,\"message\":\"Cluster selected successfully\"}";

        // Deserialize to object
        SelectClusterResponse deserialized = objectMapper.readValue(json, SelectClusterResponse.class);

        // Verify fields
        assertTrue(deserialized.isSuccess());
        assertEquals("Cluster selected successfully", deserialized.getMessage());
    }
}