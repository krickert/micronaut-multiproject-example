package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        // Verify the values
        assertEquals("test-step", deserialized.getPipelineStepId());
        assertEquals("test-module", deserialized.getPipelineImplementationId());
        assertEquals("{\"key\": \"value\"}", deserialized.getCustomConfig().getJsonConfig());
        assertEquals(1, deserialized.getKafkaListenTopics().size());
        assertEquals("test-input-topic", deserialized.getKafkaListenTopics().get(0));
        assertEquals(1, deserialized.getKafkaPublishTopics().size());
        assertEquals("test-output-topic", deserialized.getKafkaPublishTopics().get(0).getTopic());
        assertEquals(1, deserialized.getGrpcForwardTo().size());
        assertEquals("test-grpc-service", deserialized.getGrpcForwardTo().get(0));
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a PipelineStepConfig instance with null values
        PipelineStepConfig config = new PipelineStepConfig(null, null, null, null, null, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        // Verify the values
        assertNull(deserialized.getPipelineStepId());
        assertNull(deserialized.getPipelineImplementationId());
        assertNull(deserialized.getCustomConfig());
        assertNull(deserialized.getKafkaListenTopics());
        assertNull(deserialized.getKafkaPublishTopics());
        assertNull(deserialized.getGrpcForwardTo());
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
                Arrays.asList("test-input-topic"),
                kafkaPublishTopics,
                Arrays.asList("test-grpc-service")
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
            assertEquals("test-step-1", config.getPipelineStepId());
            assertEquals("test-module-1", config.getPipelineImplementationId());
            assertNotNull(config.getCustomConfig());
            assertEquals("{\"key\": \"value\", \"threshold\": 0.75}", config.getCustomConfig().getJsonConfig());
            
            assertNotNull(config.getKafkaListenTopics());
            assertEquals(2, config.getKafkaListenTopics().size());
            assertEquals("test-input-topic-1", config.getKafkaListenTopics().get(0));
            assertEquals("test-input-topic-2", config.getKafkaListenTopics().get(1));
            
            assertNotNull(config.getKafkaPublishTopics());
            assertEquals(2, config.getKafkaPublishTopics().size());
            assertEquals("test-output-topic-1", config.getKafkaPublishTopics().get(0).getTopic());
            assertEquals("test-output-topic-2", config.getKafkaPublishTopics().get(1).getTopic());
            
            assertNotNull(config.getGrpcForwardTo());
            assertEquals(2, config.getGrpcForwardTo().size());
            assertEquals("test-grpc-service-1", config.getGrpcForwardTo().get(0));
            assertEquals("test-grpc-service-2", config.getGrpcForwardTo().get(1));
        }
    }
}