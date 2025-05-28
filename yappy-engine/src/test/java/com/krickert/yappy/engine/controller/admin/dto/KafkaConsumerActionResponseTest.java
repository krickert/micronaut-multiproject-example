package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KafkaConsumerActionResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        KafkaConsumerActionResponse original = new KafkaConsumerActionResponse(
                true,
                "Action completed successfully"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        KafkaConsumerActionResponse deserialized = objectMapper.readValue(json, KafkaConsumerActionResponse.class);

        // Verify all fields match
        assertTrue(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
    }

    @Test
    void testSerializationWithFailureResponse() throws Exception {
        // Create an instance with failure response
        KafkaConsumerActionResponse original = new KafkaConsumerActionResponse(
                false,
                "Action failed: Consumer not found"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        KafkaConsumerActionResponse deserialized = objectMapper.readValue(json, KafkaConsumerActionResponse.class);

        // Verify all fields match
        assertFalse(deserialized.isSuccess());
        assertEquals(original.getMessage(), deserialized.getMessage());
    }

    @Test
    void testDeserializationFromJson() throws Exception {
        // JSON representation
        String json = "{\"success\":true,\"message\":\"Action completed successfully\"}";

        // Deserialize to object
        KafkaConsumerActionResponse deserialized = objectMapper.readValue(json, KafkaConsumerActionResponse.class);

        // Verify fields
        assertTrue(deserialized.isSuccess());
        assertEquals("Action completed successfully", deserialized.getMessage());
    }
}