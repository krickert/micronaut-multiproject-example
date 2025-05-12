package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
                List.of("test-grpc-service"),
                List.of("next-step-1"),      // nextSteps
                List.of("error-step-1")       // errorSteps
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
        assertEquals(1, deserialized.nextSteps().size());
        assertEquals("next-step-1", deserialized.nextSteps().getFirst());
        assertEquals(1, deserialized.errorSteps().size());
        assertEquals("error-step-1", deserialized.errorSteps().getFirst());
    }

    @Test
    void testValidation() {
        // Test null pipelineStepId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                null, "test-module", null, null, null, null, null, null));

        // Test blank pipelineStepId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "", "test-module", null, null, null, null, null, null));

        // Test null pipelineImplementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "test-step", null, null, null, null, null, null, null));

        // Test blank pipelineImplementationId validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "test-step", "", null, null, null, null, null, null));

        // Test null collections handling (should not throw, converts to empty collections)
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step", "test-module", null, null, null, null, null, null);
        assertTrue(config.kafkaListenTopics().isEmpty());
        assertTrue(config.kafkaPublishTopics().isEmpty());
        assertTrue(config.grpcForwardTo().isEmpty());
        assertTrue(config.nextSteps().isEmpty());
        assertTrue(config.errorSteps().isEmpty());

        // Test validation for elements within new lists
        List<String> nextStepsWithNull = new ArrayList<>();
        nextStepsWithNull.add("valid");
        nextStepsWithNull.add(null); // Add null to a mutable list
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                        "test-step", "test-module", null, null, null, null, nextStepsWithNull, null),
                "nextSteps should not allow null elements");

        List<String> nextStepsWithBlank = new ArrayList<>();
        nextStepsWithBlank.add("valid");
        nextStepsWithBlank.add(""); // Add blank to a mutable list
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                        "test-step", "test-module", null, null, null, null, nextStepsWithBlank, null),
                "nextSteps should not allow blank elements");

        List<String> errorStepsWithNull = new ArrayList<>();
        errorStepsWithNull.add("valid");
        errorStepsWithNull.add(null); // Add null to a mutable list
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                        "test-step", "test-module", null, null, null, null, null, errorStepsWithNull),
                "errorSteps should not allow null elements");

        List<String> errorStepsWithBlank = new ArrayList<>();
        errorStepsWithBlank.add("valid");
        errorStepsWithBlank.add(""); // Add blank to a mutable list
        assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                        "test-step", "test-module", null, null, null, null, null, errorStepsWithBlank),
                "errorSteps should not allow blank elements");
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
                List.of("test-grpc-service"),
                List.of("next-step-1"),      // nextSteps
                List.of("error-step-1")       // errorSteps
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
        assertTrue(json.contains("\"nextSteps\":[\"next-step-1\"]"));
        assertTrue(json.contains("\"errorSteps\":[\"error-step-1\"]"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        // Assuming your pipeline-step-config.json might also include nextSteps and errorSteps
        // If not, this part of the test might need adjustment or the JSON file needs an update.
        try (InputStream is = getClass().getResourceAsStream("/pipeline-step-config.json")) {
            assertNotNull(is, "Could not load resource /pipeline-step-config.json");
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

            // Verify new fields - these will be empty if not in the JSON and NON_NULL include is used
            // Or they will be null if NON_NULL is not strictly hiding them and they are absent
            // The canonical constructor initializes them to empty lists if null is passed during construction,
            // and Jackson should do similarly if the fields are absent in JSON.
            assertNotNull(config.nextSteps(), "nextSteps should not be null after deserialization");
            assertNotNull(config.errorSteps(), "errorSteps should not be null after deserialization");

            // Example: If your JSON file *does* contain these:
            // assertEquals(1, config.nextSteps().size());
            // assertEquals("next-step-from-json", config.nextSteps().get(0));
            // assertEquals(1, config.errorSteps().size());
            // assertEquals("error-step-from-json", config.errorSteps().get(0));

            // If your JSON file *does not* contain these, they should be empty lists due to the canonical constructor's logic
            // if Jackson calls it or a similar default for missing collection fields.
            assertTrue(config.nextSteps().isEmpty(), "Expected nextSteps to be empty if not in JSON");
            assertTrue(config.errorSteps().isEmpty(), "Expected errorSteps to be empty if not in JSON");
        }
    }
}