package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SetConsulConfigRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        SetConsulConfigRequest original = new SetConsulConfigRequest(
                "localhost",
                8500,
                "secret-token"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SetConsulConfigRequest deserialized = objectMapper.readValue(json, SetConsulConfigRequest.class);

        // Verify all fields match
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getPort(), deserialized.getPort());
        assertEquals(original.getAclToken(), deserialized.getAclToken());
    }

    @Test
    void testSerializationWithNullToken() throws Exception {
        // Create an instance with null token
        SetConsulConfigRequest original = new SetConsulConfigRequest(
                "localhost",
                8500,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        SetConsulConfigRequest deserialized = objectMapper.readValue(json, SetConsulConfigRequest.class);

        // Verify all fields match
        assertEquals(original.getHost(), deserialized.getHost());
        assertEquals(original.getPort(), deserialized.getPort());
        assertNull(deserialized.getAclToken());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"host\":\"localhost\",\"port\":8500}";

        // Deserialize to object
        SetConsulConfigRequest deserialized = objectMapper.readValue(json, SetConsulConfigRequest.class);

        // Verify fields
        assertEquals("localhost", deserialized.getHost());
        assertEquals(8500, deserialized.getPort());
        assertNull(deserialized.getAclToken());
    }
}