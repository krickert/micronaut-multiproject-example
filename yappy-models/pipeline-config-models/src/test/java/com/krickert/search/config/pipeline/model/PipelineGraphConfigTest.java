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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineGraphConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a map of PipelineConfig instances
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Create a pipeline for the map
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step for the pipeline
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
                null, // nextSteps - assuming null is acceptable and becomes emptyList
                null  // errorSteps - assuming null is acceptable and becomes emptyList
        );
        steps.put("test-step", step);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        pipelines.put("test-pipeline", pipeline);

        // Create a PipelineGraphConfig instance
        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        // Verify the values
        assertNotNull(deserialized.pipelines());
        assertEquals(1, deserialized.pipelines().size());

        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline");
        assertNotNull(deserializedPipeline);
        assertEquals("test-pipeline", deserializedPipeline.name());
        assertNotNull(deserializedPipeline.pipelineSteps());
        assertEquals(1, deserializedPipeline.pipelineSteps().size());

        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("test-step");
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
        // Verify new fields are present and empty (or as expected if you provided values)
        assertNotNull(deserializedStep.nextSteps());
        assertTrue(deserializedStep.nextSteps().isEmpty());
        assertNotNull(deserializedStep.errorSteps());
        assertTrue(deserializedStep.errorSteps().isEmpty());
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a PipelineGraphConfig instance with null values
        PipelineGraphConfig config = new PipelineGraphConfig(null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        // Verify the values
        // The canonical constructor of PipelineGraphConfig should ensure pipelines is an empty map if null is passed.
        assertNotNull(deserialized.pipelines());
        assertTrue(deserialized.pipelines().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a map of PipelineConfig instances
        Map<String, PipelineConfig> pipelines = new HashMap<>();

        // Create a pipeline for the map
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        // Create a step for the pipeline
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
                List.of("next-step-id"), // Example nextSteps
                List.of("error-step-id")  // Example errorSteps
        );
        steps.put("test-step", step);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        pipelines.put("test-pipeline", pipeline);

        // Create a PipelineGraphConfig instance
        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"pipelines\":"));
        assertTrue(json.contains("\"test-pipeline\":"));
        // Check for new fields in the step's JSON representation within the graph
        assertTrue(json.contains("\"nextSteps\":[\"next-step-id\"]"));
        assertTrue(json.contains("\"errorSteps\":[\"error-step-id\"]"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-graph-config.json")) {
            assertNotNull(is, "Could not load resource /pipeline-graph-config.json");
            // Deserialize from JSON
            PipelineGraphConfig config = objectMapper.readValue(is, PipelineGraphConfig.class);

            // Verify the values
            assertNotNull(config.pipelines());
            assertEquals(2, config.pipelines().size());

            // Verify first pipeline
            PipelineConfig pipeline1 = config.pipelines().get("pipeline1");
            assertNotNull(pipeline1);
            assertEquals("pipeline1", pipeline1.name());
            assertNotNull(pipeline1.pipelineSteps());
            assertEquals(2, pipeline1.pipelineSteps().size());

            // Verify first step of first pipeline
            PipelineStepConfig step1 = pipeline1.pipelineSteps().get("step1");
            assertNotNull(step1);
            assertEquals("step1", step1.pipelineStepId());
            assertEquals("test-module-1", step1.pipelineImplementationId());
            // Add assertions for nextSteps and errorSteps if they are in your JSON for step1
            // e.g., assertTrue(step1.nextSteps().contains("some-next-step-for-step1"));

            // Verify second step of first pipeline
            PipelineStepConfig step2 = pipeline1.pipelineSteps().get("step2");
            assertNotNull(step2);
            assertEquals("step2", step2.pipelineStepId());
            assertEquals("test-module-2", step2.pipelineImplementationId());
            // Add assertions for nextSteps and errorSteps if they are in your JSON for step2

            // Verify second pipeline
            PipelineConfig pipeline2 = config.pipelines().get("pipeline2");
            assertNotNull(pipeline2);
            assertEquals("pipeline2", pipeline2.name());
            assertNotNull(pipeline2.pipelineSteps());
            assertEquals(1, pipeline2.pipelineSteps().size());

            // Verify first step of second pipeline
            PipelineStepConfig step3 = pipeline2.pipelineSteps().get("step1"); // Assuming step ID is "step1" in pipeline2
            assertNotNull(step3);
            assertEquals("step1", step3.pipelineStepId());
            assertEquals("test-module-3", step3.pipelineImplementationId());
            // Add assertions for nextSteps and errorSteps if they are in your JSON for step3

            // General check for one of the steps (assuming step1 of pipeline1 might have them)
            // Adjust based on your actual JSON file content
            PipelineStepConfig exampleStep = pipeline1.pipelineSteps().get("step1");
            if (exampleStep != null) {
                assertNotNull(exampleStep.nextSteps());
                assertNotNull(exampleStep.errorSteps());
                // If your JSON for this step doesn't include nextSteps/errorSteps, they should be empty
                // Example: if pipeline-graph-config.json has nextSteps for pipeline1/step1:
                // assertEquals(1, exampleStep.nextSteps().size());
                // assertEquals("expected-next-step-id", exampleStep.nextSteps().getFirst());
            }
        }
    }
}