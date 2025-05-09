package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
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
        assertNotNull(deserialized.getPipelines());
        assertEquals(1, deserialized.getPipelines().size());
        
        PipelineConfig deserializedPipeline = deserialized.getPipelines().get("test-pipeline");
        assertNotNull(deserializedPipeline);
        assertEquals("test-pipeline", deserializedPipeline.getName());
        assertNotNull(deserializedPipeline.getPipelineSteps());
        assertEquals(1, deserializedPipeline.getPipelineSteps().size());
        
        PipelineStepConfig deserializedStep = deserializedPipeline.getPipelineSteps().get("test-step");
        assertNotNull(deserializedStep);
        assertEquals("test-step", deserializedStep.getPipelineStepId());
        assertEquals("test-module", deserializedStep.getPipelineImplementationId());
        assertEquals("{\"key\": \"value\"}", deserializedStep.getCustomConfig().getJsonConfig());
        assertEquals(1, deserializedStep.getKafkaListenTopics().size());
        assertEquals("test-input-topic", deserializedStep.getKafkaListenTopics().get(0));
        assertEquals(1, deserializedStep.getKafkaPublishTopics().size());
        assertEquals("test-output-topic", deserializedStep.getKafkaPublishTopics().get(0).getTopic());
        assertEquals(1, deserializedStep.getGrpcForwardTo().size());
        assertEquals("test-grpc-service", deserializedStep.getGrpcForwardTo().get(0));
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
        assertNull(deserialized.getPipelines());
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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
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
            assertNotNull(config.getPipelines());
            assertEquals(2, config.getPipelines().size());
            
            // Verify first pipeline
            PipelineConfig pipeline1 = config.getPipelines().get("pipeline1");
            assertNotNull(pipeline1);
            assertEquals("pipeline1", pipeline1.getName());
            assertNotNull(pipeline1.getPipelineSteps());
            assertEquals(2, pipeline1.getPipelineSteps().size());
            
            // Verify first step of first pipeline
            PipelineStepConfig step1 = pipeline1.getPipelineSteps().get("step1");
            assertNotNull(step1);
            assertEquals("step1", step1.getPipelineStepId());
            assertEquals("test-module-1", step1.getPipelineImplementationId());
            
            // Verify second step of first pipeline
            PipelineStepConfig step2 = pipeline1.getPipelineSteps().get("step2");
            assertNotNull(step2);
            assertEquals("step2", step2.getPipelineStepId());
            assertEquals("test-module-2", step2.getPipelineImplementationId());
            
            // Verify second pipeline
            PipelineConfig pipeline2 = config.getPipelines().get("pipeline2");
            assertNotNull(pipeline2);
            assertEquals("pipeline2", pipeline2.getName());
            assertNotNull(pipeline2.getPipelineSteps());
            assertEquals(1, pipeline2.getPipelineSteps().size());
            
            // Verify first step of second pipeline
            PipelineStepConfig step3 = pipeline2.getPipelineSteps().get("step1");
            assertNotNull(step3);
            assertEquals("step1", step3.getPipelineStepId());
            assertEquals("test-module-3", step3.getPipelineImplementationId());
        }
    }
}