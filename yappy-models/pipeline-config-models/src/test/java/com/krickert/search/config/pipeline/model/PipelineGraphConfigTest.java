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
                List.of("test-grpc-service")
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
                List.of("test-grpc-service")
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
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-graph-config.json")) {
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

            // Verify second step of first pipeline
            PipelineStepConfig step2 = pipeline1.pipelineSteps().get("step2");
            assertNotNull(step2);
            assertEquals("step2", step2.pipelineStepId());
            assertEquals("test-module-2", step2.pipelineImplementationId());

            // Verify second pipeline
            PipelineConfig pipeline2 = config.pipelines().get("pipeline2");
            assertNotNull(pipeline2);
            assertEquals("pipeline2", pipeline2.name());
            assertNotNull(pipeline2.pipelineSteps());
            assertEquals(1, pipeline2.pipelineSteps().size());

            // Verify first step of second pipeline
            PipelineStepConfig step3 = pipeline2.pipelineSteps().get("step1");
            assertNotNull(step3);
            assertEquals("step1", step3.pipelineStepId());
            assertEquals("test-module-3", step3.pipelineImplementationId());
        }
    }
}
