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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
        );
        steps.put("test-step", step);
        
        // Create a PipelineConfig instance
        PipelineConfig config = new PipelineConfig("test-pipeline", steps);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        // Verify the values
        assertEquals("test-pipeline", deserialized.getName());
        assertNotNull(deserialized.getPipelineSteps());
        assertEquals(1, deserialized.getPipelineSteps().size());
        
        PipelineStepConfig deserializedStep = deserialized.getPipelineSteps().get("test-step");
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
        // Create a PipelineConfig instance with null values
        PipelineConfig config = new PipelineConfig(null, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        // Verify the values
        assertNull(deserialized.getName());
        assertNull(deserialized.getPipelineSteps());
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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
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
            assertEquals("test-pipeline", config.getName());
            assertNotNull(config.getPipelineSteps());
            assertEquals(2, config.getPipelineSteps().size());
            
            // Verify first step
            PipelineStepConfig step1 = config.getPipelineSteps().get("step1");
            assertNotNull(step1);
            assertEquals("step1", step1.getPipelineStepId());
            assertEquals("test-module-1", step1.getPipelineImplementationId());
            assertEquals("{\"key\": \"value\", \"threshold\": 0.75}", step1.getCustomConfig().getJsonConfig());
            assertEquals(1, step1.getKafkaListenTopics().size());
            assertEquals("test-input-topic-1", step1.getKafkaListenTopics().get(0));
            assertEquals(1, step1.getKafkaPublishTopics().size());
            assertEquals("intermediate-topic-1", step1.getKafkaPublishTopics().get(0).getTopic());
            assertEquals(0, step1.getGrpcForwardTo().size());
            
            // Verify second step
            PipelineStepConfig step2 = config.getPipelineSteps().get("step2");
            assertNotNull(step2);
            assertEquals("step2", step2.getPipelineStepId());
            assertEquals("test-module-2", step2.getPipelineImplementationId());
            assertEquals("{\"key2\": \"value2\", \"limit\": 100}", step2.getCustomConfig().getJsonConfig());
            assertEquals(1, step2.getKafkaListenTopics().size());
            assertEquals("intermediate-topic-1", step2.getKafkaListenTopics().get(0));
            assertEquals(1, step2.getKafkaPublishTopics().size());
            assertEquals("test-output-topic-1", step2.getKafkaPublishTopics().get(0).getTopic());
            assertEquals(1, step2.getGrpcForwardTo().size());
            assertEquals("test-grpc-service-1", step2.getGrpcForwardTo().get(0));
        }
    }
}