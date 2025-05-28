package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaConsumerActionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create an instance with all fields populated
        KafkaConsumerActionRequest original = new KafkaConsumerActionRequest(
                "test-pipeline",
                "test-step",
                "test-topic",
                "test-group-id"
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        KafkaConsumerActionRequest deserialized = objectMapper.readValue(json, KafkaConsumerActionRequest.class);

        // Verify all fields match
        assertEquals(original.getPipelineName(), deserialized.getPipelineName());
        assertEquals(original.getStepName(), deserialized.getStepName());
        assertEquals(original.getTopic(), deserialized.getTopic());
        assertEquals(original.getGroupId(), deserialized.getGroupId());
    }

    @Test
    void testDeserializationFromJson() throws Exception {
        // JSON representation
        String json = "{\"pipelineName\":\"test-pipeline\",\"stepName\":\"test-step\",\"topic\":\"test-topic\",\"groupId\":\"test-group-id\"}";

        // Deserialize to object
        KafkaConsumerActionRequest deserialized = objectMapper.readValue(json, KafkaConsumerActionRequest.class);

        // Verify fields
        assertEquals("test-pipeline", deserialized.getPipelineName());
        assertEquals("test-step", deserialized.getStepName());
        assertEquals("test-topic", deserialized.getTopic());
        assertEquals("test-group-id", deserialized.getGroupId());
    }
}