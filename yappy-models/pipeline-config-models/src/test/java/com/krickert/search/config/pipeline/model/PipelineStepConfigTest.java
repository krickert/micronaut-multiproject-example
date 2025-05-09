package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineStepConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a JsonConfigOptions for the test
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\": \"value\"}");

        // Create a list of KafkaPublishTopic for the test
        List<KafkaPublishTopic> kafkaPublishTopics = new ArrayList<>();
        kafkaPublishTopics.add(new KafkaPublishTopic("test-output-topic"));

        // Create a PipelineStepConfig instance
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step",
                "test-module",
                customConfig,
                List.of("test-input-topic"),
                kafkaPublishTopics,
                List.of("test-grpc-service")
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        // Verify the values
        assertEquals("test-step", deserialized.pipelineStepId());
        assertEquals("test-module", deserialized.pipelineImplementationId());
        assertEquals("{\"key\": \"value\"}", deserialized.customConfig().jsonConfig());
        assertEquals(1, deserialized.kafkaListenTopics().size());
        assertEquals("test-input-topic", deserialized.kafkaListenTopics().getFirst());
        assertEquals(1, deserialized.kafkaPublishTopics().size());
        assertEquals("test-output-topic", deserialized.kafkaPublishTopics().getFirst().topic());
        assertEquals(1, deserialized.grpcForwardTo().size());
        assertEquals("test-grpc-service", deserialized.grpcForwardTo().getFirst());
    }

    @Test
    void testValidation() {
        // Test null pipelineStepId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                null, "test-module", null, null, null, null));

        // Test blank pipelineStepId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "", "test-module", null, null, null, null));

        // Test null pipelineImplementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "test-step", null, null, null, null, null));

        // Test blank pipelineImplementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "test-step", "", null, null, null, null));

        // Test null collections handling (should not throw, converts to empty collections)
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step", "test-module", null, null, null, null);
        assertTrue(config.kafkaListenTopics().isEmpty());
        assertTrue(config.kafkaPublishTopics().isEmpty());
        assertTrue(config.grpcForwardTo().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a JsonConfigOptions for the test
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\": \"value\"}");

        // Create a list of KafkaPublishTopic for the test
        List<KafkaPublishTopic> kafkaPublishTopics = new ArrayList<>();
        kafkaPublishTopics.add(new KafkaPublishTopic("test-output-topic"));

        // Create a PipelineStepConfig instance
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step",
                "test-module",
                customConfig,
                List.of("test-input-topic"),
                kafkaPublishTopics,
                List.of("test-grpc-service")
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"pipelineStepId\":\"test-step\""));
        assertTrue(json.contains("\"pipelineImplementationId\":\"test-module\""));
        assertTrue(json.contains("\"customConfig\":"));
        assertTrue(json.contains("\"kafkaListenTopics\":"));
        assertTrue(json.contains("\"kafkaPublishTopics\":"));
        assertTrue(json.contains("\"grpcForwardTo\":"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-step-config.json")) {
            // Deserialize from JSON
            PipelineStepConfig config = objectMapper.readValue(is, PipelineStepConfig.class);

            // Verify the values
            assertEquals("test-step-1", config.pipelineStepId());
            assertEquals("test-module-1", config.pipelineImplementationId());
            assertNotNull(config.customConfig());
            assertEquals("{\"key\": \"value\", \"threshold\": 0.75}", config.customConfig().jsonConfig());

            assertNotNull(config.kafkaListenTopics());
            assertEquals(2, config.kafkaListenTopics().size());
            assertEquals("test-input-topic-1", config.kafkaListenTopics().get(0));
            assertEquals("test-input-topic-2", config.kafkaListenTopics().get(1));

            assertNotNull(config.kafkaPublishTopics());
            assertEquals(2, config.kafkaPublishTopics().size());
            assertEquals("test-output-topic-1", config.kafkaPublishTopics().get(0).topic());
            assertEquals("test-output-topic-2", config.kafkaPublishTopics().get(1).topic());

            assertNotNull(config.grpcForwardTo());
            assertEquals(2, config.grpcForwardTo().size());
            assertEquals("test-grpc-service-1", config.grpcForwardTo().get(0));
            assertEquals("test-grpc-service-2", config.grpcForwardTo().get(1));
        }
    }
}
