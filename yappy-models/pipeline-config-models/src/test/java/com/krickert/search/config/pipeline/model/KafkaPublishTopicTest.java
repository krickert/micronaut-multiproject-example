package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("test-topic", deserialized.getTopic());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a KafkaPublishTopic instance with null values
        KafkaPublishTopic topic = new KafkaPublishTopic(null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(topic);

        // Deserialize from JSON
        KafkaPublishTopic deserialized = objectMapper.readValue(json, KafkaPublishTopic.class);

        // Verify the values
        assertNull(deserialized.getTopic());
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
            assertEquals("test-output-topic", topic.getTopic());
        }
    }
}