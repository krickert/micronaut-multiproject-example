package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CreateClusterResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        CreateClusterResponse original = new CreateClusterResponse(
                true,
                "Cluster created successfully",
                "test-cluster",
                "yappy/pipeline-clusters/test-cluster"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        CreateClusterResponse deserialized = objectMapper.readValue(json, CreateClusterResponse.class);

        // Verify all fields match
        assertTrue(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
        assertEquals(original.getClusterName(), deserialized.getClusterName());
        assertEquals(original.getSeededConfigPath(), deserialized.getSeededConfigPath());
    }

    @Test
    void testSerializationWithNullFields() throws Exception {
        // Create an instance with some null fields
        CreateClusterResponse original = new CreateClusterResponse(
                false,
                "Failed to create cluster",
                null,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        CreateClusterResponse deserialized = objectMapper.readValue(json, CreateClusterResponse.class);

        // Verify all fields match
        assertFalse(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
        assertNull(deserialized.getClusterName());
        assertNull(deserialized.getSeededConfigPath());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"success\":true,\"message\":\"Cluster created successfully\"}";

        // Deserialize to object
        CreateClusterResponse deserialized = objectMapper.readValue(json, CreateClusterResponse.class);

        // Verify fields
        assertTrue(deserialized.isSuccess());
        assertEquals("Cluster created successfully", deserialized.getMessage());
        assertNull(deserialized.getClusterName());
        assertNull(deserialized.getSeededConfigPath());
    }
}