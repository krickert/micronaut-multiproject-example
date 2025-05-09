package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SchemaReferenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a SchemaReference instance
        SchemaReference reference = new SchemaReference("test-subject", 123);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(reference);

        // Deserialize from JSON
        SchemaReference deserialized = objectMapper.readValue(json, SchemaReference.class);

        // Verify the values
        assertEquals("test-subject", deserialized.getSubject());
        assertEquals(123, deserialized.getVersion());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a SchemaReference instance with null values
        SchemaReference reference = new SchemaReference(null, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(reference);

        // Deserialize from JSON
        SchemaReference deserialized = objectMapper.readValue(json, SchemaReference.class);

        // Verify the values
        assertNull(deserialized.getSubject());
        assertNull(deserialized.getVersion());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a SchemaReference instance
        SchemaReference reference = new SchemaReference("test-subject", 123);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(reference);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"subject\":\"test-subject\""));
        assertTrue(json.contains("\"version\":123"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/schema-reference.json")) {
            // Deserialize from JSON
            SchemaReference reference = objectMapper.readValue(is, SchemaReference.class);

            // Verify the values
            assertEquals("test-schema", reference.getSubject());
            assertEquals(42, reference.getVersion());
        }
    }

    private void assertTrue(boolean condition) {
        if (!condition) {
            throw new AssertionError("Assertion failed");
        }
    }
}
