package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConsulConfigResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        ConsulConfigResponse original = new ConsulConfigResponse(
                "localhost", 
                "8500", 
                "secret-token", 
                "test-cluster");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        ConsulConfigResponse deserialized = objectMapper.readValue(json, ConsulConfigResponse.class);

        // Verify all fields match
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getPort(), deserialized.getPort());
        assertEquals(original.getAclToken(), deserialized.getAclToken());
        assertEquals(original.getSelectedYappyClusterName(), deserialized.getSelectedYappyClusterName());
    }

    @Test
    void testSerializationWithNullFields() throws Exception {
        // Create an instance with some null fields
        ConsulConfigResponse original = new ConsulConfigResponse(
                "localhost", 
                "8500", 
                null, 
                null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        ConsulConfigResponse deserialized = objectMapper.readValue(json, ConsulConfigResponse.class);

        // Verify all fields match
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getPort(), deserialized.getPort());
        assertNull(deserialized.getAclToken());
        assertNull(deserialized.getSelectedYappyClusterName());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"host\":\"localhost\",\"port\":\"8500\"}";

        // Deserialize to object
        ConsulConfigResponse deserialized = objectMapper.readValue(json, ConsulConfigResponse.class);

        // Verify fields
        assertEquals("localhost", deserialized.getHost());
        assertEquals("8500", deserialized.getPort());
        assertNull(deserialized.getAclToken());
        assertNull(deserialized.getSelectedYappyClusterName());
    }
}