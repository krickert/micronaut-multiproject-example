package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SelectClusterRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        SelectClusterRequest original = new SelectClusterRequest("test-cluster");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SelectClusterRequest deserialized = objectMapper.readValue(json, SelectClusterRequest.class);

        // Verify all fields match
        assertEquals(original.getClusterName(), deserialized.getClusterName());
    }

    @Test
    void testDeserializationFromJson() throws Exception {
        // JSON representation
        String json = "{\"clusterName\":\"test-cluster\"}";

        // Deserialize to object
        SelectClusterRequest deserialized = objectMapper.readValue(json, SelectClusterRequest.class);

        // Verify fields
        assertEquals("test-cluster", deserialized.getClusterName());
    }
}