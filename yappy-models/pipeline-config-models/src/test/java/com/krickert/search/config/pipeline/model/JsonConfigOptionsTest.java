package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonConfigOptionsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a JsonConfigOptions instance
        JsonConfigOptions options = new JsonConfigOptions("{\"key\": \"value\"}");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(options);

        // Deserialize from JSON
        JsonConfigOptions deserialized = objectMapper.readValue(json, JsonConfigOptions.class);

        // Verify the values
        assertEquals("{\"key\": \"value\"}", deserialized.jsonConfig());
    }

    @Test
    void testValidation() {
        // Test null jsonConfig validation
        assertThrows(IllegalArgumentException.class, () -> new JsonConfigOptions(null));
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a JsonConfigOptions instance
        JsonConfigOptions options = new JsonConfigOptions("{\"key\": \"value\"}");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(options);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"jsonConfig\":\"{\\\"key\\\": \\\"value\\\"}\""));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/json-config-options.json")) {
            // Deserialize from JSON
            JsonConfigOptions options = objectMapper.readValue(is, JsonConfigOptions.class);

            // Verify the values
            assertEquals("{\"key\": \"value\", \"nested\": {\"nestedKey\": \"nestedValue\"}}", options.jsonConfig());
        }
    }

    @Test
    void testDefaultValue() {
        // Create a JsonConfigOptions instance with default constructor
        JsonConfigOptions options = new JsonConfigOptions();

        // Verify the default value
        assertEquals("{}", options.jsonConfig());
    }
}
