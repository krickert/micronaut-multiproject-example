package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SetConsulConfigResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create a config response
        ConsulConfigResponse config = new ConsulConfigResponse(
                "localhost", 
                "8500", 
                "secret-token", 
                "test-cluster");

        // Create an instance with all fields populated
        SetConsulConfigResponse original = new SetConsulConfigResponse(
                true,
                "Configuration set successfully",
                config
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SetConsulConfigResponse deserialized = objectMapper.readValue(json, SetConsulConfigResponse.class);

        // Verify all fields match
        assertTrue(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
        assertEquals(original.getCurrentConfig().getHost(), deserialized.getCurrentConfig().getHost());
        assertEquals(original.getCurrentConfig().getPort(), deserialized.getCurrentConfig().getPort());
        assertEquals(original.getCurrentConfig().getAclToken(), deserialized.getCurrentConfig().getAclToken());
        assertEquals(original.getCurrentConfig().getSelectedYappyClusterName(), 
                     deserialized.getCurrentConfig().getSelectedYappyClusterName());
    }

    @Test
    void testSerializationWithFailureResponse() throws Exception {
        // Create an instance with failure response
        SetConsulConfigResponse original = new SetConsulConfigResponse(
                false,
                "Failed to set configuration: Invalid host",
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SetConsulConfigResponse deserialized = objectMapper.readValue(json, SetConsulConfigResponse.class);

        // Verify all fields match
        assertFalse(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
        assertNull(deserialized.getCurrentConfig());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"success\":true,\"message\":\"Configuration set successfully\"}";

        // Deserialize to object
        SetConsulConfigResponse deserialized = objectMapper.readValue(json, SetConsulConfigResponse.class);

        // Verify fields
        assertTrue(deserialized.isSuccess());
        assertEquals("Configuration set successfully", deserialized.getMessage());
        assertNull(deserialized.getCurrentConfig());
    }
}