package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a map of PipelineStepConfig instances
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step for the map
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\": \"value\"}");
        List<KafkaPublishTopic> kafkaPublishTopics = new ArrayList<>();
        kafkaPublishTopics.add(new KafkaPublishTopic("test-output-topic"));

        PipelineStepConfig step = new PipelineStepConfig(
                "test-step",
                "test-module",
                customConfig,
                List.of("test-input-topic"),
                kafkaPublishTopics,
                List.of("test-grpc-service")
        );
        steps.put("test-step", step);

        // Create a PipelineConfig instance
        PipelineConfig config = new PipelineConfig("test-pipeline", steps);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        // Verify the values
        assertEquals("test-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertEquals(1, deserialized.pipelineSteps().size());

        PipelineStepConfig deserializedStep = deserialized.pipelineSteps().get("test-step");
        assertNotNull(deserializedStep);
        assertEquals("test-step", deserializedStep.pipelineStepId());
        assertEquals("test-module", deserializedStep.pipelineImplementationId());
        assertEquals("{\"key\": \"value\"}", deserializedStep.customConfig().jsonConfig());
        assertEquals(1, deserializedStep.kafkaListenTopics().size());
        assertEquals("test-input-topic", deserializedStep.kafkaListenTopics().getFirst());
        assertEquals(1, deserializedStep.kafkaPublishTopics().size());
        assertEquals("test-output-topic", deserializedStep.kafkaPublishTopics().getFirst().topic());
        assertEquals(1, deserializedStep.grpcForwardTo().size());
        assertEquals("test-grpc-service", deserializedStep.grpcForwardTo().getFirst());
    }

    @Test
    void testValidation() {
        // Test null name validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, Map.of()));

        // Test blank name validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig("", Map.of()));

        // Test null pipelineSteps handling (should not throw, converts to empty map)
        PipelineConfig config = new PipelineConfig("test-pipeline", null);
        assertTrue(config.pipelineSteps().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a map of PipelineStepConfig instances
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step for the map
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"key\": \"value\"}");
        List<KafkaPublishTopic> kafkaPublishTopics = new ArrayList<>();
        kafkaPublishTopics.add(new KafkaPublishTopic("test-output-topic"));

        PipelineStepConfig step = new PipelineStepConfig(
                "test-step",
                "test-module",
                customConfig,
                List.of("test-input-topic"),
                kafkaPublishTopics,
                List.of("test-grpc-service")
        );
        steps.put("test-step", step);

        // Create a PipelineConfig instance
        PipelineConfig config = new PipelineConfig("test-pipeline", steps);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"name\":\"test-pipeline\""));
        assertTrue(json.contains("\"pipelineSteps\":"));
        assertTrue(json.contains("\"test-step\":"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-config.json")) {
            // Deserialize from JSON
            PipelineConfig config = objectMapper.readValue(is, PipelineConfig.class);

            // Verify the values
            assertEquals("test-pipeline", config.name());
            assertNotNull(config.pipelineSteps());
            assertEquals(2, config.pipelineSteps().size());

            // Verify first step
            PipelineStepConfig step1 = config.pipelineSteps().get("step1");
            assertNotNull(step1);
            assertEquals("step1", step1.pipelineStepId());
            assertEquals("test-module-1", step1.pipelineImplementationId());
            assertEquals("{\"key\": \"value\", \"threshold\": 0.75}", step1.customConfig().jsonConfig());
            assertEquals(1, step1.kafkaListenTopics().size());
            assertEquals("test-input-topic-1", step1.kafkaListenTopics().getFirst());
            assertEquals(1, step1.kafkaPublishTopics().size());
            assertEquals("intermediate-topic-1", step1.kafkaPublishTopics().getFirst().topic());
            assertEquals(0, step1.grpcForwardTo().size());

            // Verify second step
            PipelineStepConfig step2 = config.pipelineSteps().get("step2");
            assertNotNull(step2);
            assertEquals("step2", step2.pipelineStepId());
            assertEquals("test-module-2", step2.pipelineImplementationId());
            assertEquals("{\"key2\": \"value2\", \"limit\": 100}", step2.customConfig().jsonConfig());
            assertEquals(1, step2.kafkaListenTopics().size());
            assertEquals("intermediate-topic-1", step2.kafkaListenTopics().getFirst());
            assertEquals(1, step2.kafkaPublishTopics().size());
            assertEquals("test-output-topic-1", step2.kafkaPublishTopics().getFirst().topic());
            assertEquals(1, step2.grpcForwardTo().size());
            assertEquals("test-grpc-service-1", step2.grpcForwardTo().getFirst());
        }
    }
}
