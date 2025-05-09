package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaPublishTopicTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a KafkaPublishTopic instance
        KafkaPublishTopic topic = new KafkaPublishTopic("test-topic");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(topic);

        // Deserialize from JSON
        KafkaPublishTopic deserialized = objectMapper.readValue(json, KafkaPublishTopic.class);

        // Verify the values
        assertEquals("test-topic", deserialized.topic());
    }

    @Test
    void testValidation() {
        // Test null topic validation
        assertThrows(IllegalArgumentException.class, () -> new KafkaPublishTopic(null));

        // Test blank topic validation
        assertThrows(IllegalArgumentException.class, () -> new KafkaPublishTopic(""));
        assertThrows(IllegalArgumentException.class, () -> new KafkaPublishTopic("  "));
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a KafkaPublishTopic instance
        KafkaPublishTopic topic = new KafkaPublishTopic("test-topic");

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(topic);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"topic\":\"test-topic\""));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/kafka-publish-topic.json")) {
            // Deserialize from JSON
            KafkaPublishTopic topic = objectMapper.readValue(is, KafkaPublishTopic.class);

            // Verify the values
            assertEquals("test-output-topic", topic.topic());
        }
    }
}
