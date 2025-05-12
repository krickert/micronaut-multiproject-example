package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
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
                List.of("test-grpc-service"),
                List.of("next-step-id"), // nextSteps
                List.of("error-step-id")  // errorSteps
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
        // Verify new fields
        assertNotNull(deserializedStep.nextSteps());
        assertEquals(1, deserializedStep.nextSteps().size());
        assertEquals("next-step-id", deserializedStep.nextSteps().getFirst());
        assertNotNull(deserializedStep.errorSteps());
        assertEquals(1, deserializedStep.errorSteps().size());
        assertEquals("error-step-id", deserializedStep.errorSteps().getFirst());
    }

    @Test
    void testValidation() {
        // Test null name validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, Map.of()));

        // Test blank name validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineConfig("", Map.of()));

        // Test null pipelineSteps handling (should not throw, converts to empty map)
        // Assuming PipelineConfig's canonical constructor handles null pipelineSteps
        PipelineConfig config = new PipelineConfig("test-pipeline", null);
        assertNotNull(config.pipelineSteps(), "pipelineSteps should be an empty map, not null");
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
                List.of("test-grpc-service"),
                List.of("next-step-id"), // nextSteps
                List.of("error-step-id")  // errorSteps
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
        // Check for new fields within the step's JSON
        assertTrue(json.contains("\"nextSteps\":[\"next-step-id\"]"));
        assertTrue(json.contains("\"errorSteps\":[\"error-step-id\"]"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-config.json")) {
            assertNotNull(is, "Could not load resource /pipeline-config.json");
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
            // Assert nextSteps and errorSteps for step1 based on your JSON file content
            assertNotNull(step1.nextSteps());
            assertNotNull(step1.errorSteps());
            // Example: if step1 in JSON has nextSteps: ["step2"] and no errorSteps
            // assertEquals(1, step1.nextSteps().size());
            // assertEquals("step2", step1.nextSteps().getFirst());
            // assertTrue(step1.errorSteps().isEmpty());


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
            // Assert nextSteps and errorSteps for step2 based on your JSON file content
            assertNotNull(step2.nextSteps());
            assertNotNull(step2.errorSteps());
            // Example: if step2 in JSON has no nextSteps and errorSteps: ["error-handler-step"]
            // assertTrue(step2.nextSteps().isEmpty());
            // assertEquals(1, step2.errorSteps().size());
            // assertEquals("error-handler-step", step2.errorSteps().getFirst());
        }
    }

    // --- New Tests ---

    @Test
    void testSerializationWithEmptySteps() throws Exception {
        PipelineConfig config = new PipelineConfig("empty-steps-pipeline", Collections.emptyMap());
        String json = objectMapper.writeValueAsString(config);
        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        assertEquals("empty-steps-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertTrue(deserialized.pipelineSteps().isEmpty());
    }

    @Test
    void testImmutabilityOfPipelineSteps() {
        // This test assumes your PipelineConfig canonical constructor uses Map.copyOf()
        Map<String, PipelineStepConfig> initialSteps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig(
                "s1", "m1", null, null, null, null, null, null);
        initialSteps.put("s1", step);

        PipelineConfig config = new PipelineConfig("immutable-test-pipeline", initialSteps);
        Map<String, PipelineStepConfig> retrievedSteps = config.pipelineSteps();

        // Attempt to modify the retrieved map
        assertThrows(UnsupportedOperationException.class, () -> retrievedSteps.put("s2", null),
                "PipelineSteps map should be unmodifiable");
        assertThrows(UnsupportedOperationException.class, retrievedSteps::clear,
                "PipelineSteps map should be unmodifiable");
    }

    @Test
    void testSerializationWithMultipleProgrammaticSteps() throws Exception {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig step1 = new PipelineStepConfig(
                "step-alpha", "module-a", new JsonConfigOptions("{\"configA\":1}"),
                List.of("in-a"), List.of(new KafkaPublishTopic("out-a")), List.of("grpc-a"),
                List.of("step-beta"), null
        );
        PipelineStepConfig step2 = new PipelineStepConfig(
                "step-beta", "module-b", new JsonConfigOptions("{\"configB\":2}"),
                List.of("in-b"), List.of(new KafkaPublishTopic("out-b")), List.of("grpc-b"),
                null, List.of("error-handler")
        );
        steps.put(step1.pipelineStepId(), step1);
        steps.put(step2.pipelineStepId(), step2);

        PipelineConfig config = new PipelineConfig("multi-step-pipeline", steps);
        String json = objectMapper.writeValueAsString(config);
        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        assertEquals("multi-step-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertEquals(2, deserialized.pipelineSteps().size());

        PipelineStepConfig dStep1 = deserialized.pipelineSteps().get("step-alpha");
        assertNotNull(dStep1);
        assertEquals("module-a", dStep1.pipelineImplementationId());
        assertEquals("{\"configA\":1}", dStep1.customConfig().jsonConfig());
        assertEquals(1, dStep1.nextSteps().size());
        assertEquals("step-beta", dStep1.nextSteps().getFirst());
        assertTrue(dStep1.errorSteps().isEmpty());


        PipelineStepConfig dStep2 = deserialized.pipelineSteps().get("step-beta");
        assertNotNull(dStep2);
        assertEquals("module-b", dStep2.pipelineImplementationId());
        assertEquals("{\"configB\":2}", dStep2.customConfig().jsonConfig());
        assertTrue(dStep2.nextSteps().isEmpty());
        assertEquals(1, dStep2.errorSteps().size());
        assertEquals("error-handler", dStep2.errorSteps().getFirst());
    }
}