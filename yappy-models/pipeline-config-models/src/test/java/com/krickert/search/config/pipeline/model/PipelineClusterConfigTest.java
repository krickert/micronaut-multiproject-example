package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineClusterConfigTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a PipelineGraphConfig for the test
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        
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
        
        PipelineGraphConfig pipelineGraphConfig = new PipelineGraphConfig(pipelines);
        
        // Create a PipelineModuleMap for the test
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        SchemaReference schemaReference = new SchemaReference("test-schema", 1);
        PipelineModuleConfiguration module = new PipelineModuleConfiguration(
                "Test Module", 
                "test-module", 
                schemaReference);
        modules.put("test-module", module);
        
        PipelineModuleMap pipelineModuleMap = new PipelineModuleMap(modules);
        
        // Create allowed Kafka topics and gRPC services
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("test-input-topic", "test-output-topic"));
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("test-grpc-service"));
        
        // Create a PipelineClusterConfig instance
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                pipelineGraphConfig,
                pipelineModuleMap,
                allowedKafkaTopics,
                allowedGrpcServices
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        // Verify the values
        assertEquals("test-cluster", deserialized.getClusterName());
        
        // Verify PipelineGraphConfig
        assertNotNull(deserialized.getPipelineGraphConfig());
        assertNotNull(deserialized.getPipelineGraphConfig().getPipelines());
        assertEquals(1, deserialized.getPipelineGraphConfig().getPipelines().size());
        
        // Verify PipelineModuleMap
        assertNotNull(deserialized.getPipelineModuleMap());
        assertNotNull(deserialized.getPipelineModuleMap().getAvailableModules());
        assertEquals(1, deserialized.getPipelineModuleMap().getAvailableModules().size());
        
        // Verify allowed Kafka topics and gRPC services
        assertNotNull(deserialized.getAllowedKafkaTopics());
        assertEquals(2, deserialized.getAllowedKafkaTopics().size());
        assertTrue(deserialized.getAllowedKafkaTopics().contains("test-input-topic"));
        assertTrue(deserialized.getAllowedKafkaTopics().contains("test-output-topic"));
        
        assertNotNull(deserialized.getAllowedGrpcServices());
        assertEquals(1, deserialized.getAllowedGrpcServices().size());
        assertTrue(deserialized.getAllowedGrpcServices().contains("test-grpc-service"));
    }

    @Test
    void testNullHandling() throws Exception {
        // Create a PipelineClusterConfig instance with null values
        PipelineClusterConfig config = new PipelineClusterConfig(null, null, null, null, null);

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Deserialize from JSON
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        // Verify the values
        assertNull(deserialized.getClusterName());
        assertNull(deserialized.getPipelineGraphConfig());
        assertNull(deserialized.getPipelineModuleMap());
        assertNull(deserialized.getAllowedKafkaTopics());
        assertNull(deserialized.getAllowedGrpcServices());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a simple PipelineClusterConfig instance
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                new PipelineGraphConfig(new HashMap<>()),
                new PipelineModuleMap(new HashMap<>()),
                new HashSet<>(Arrays.asList("topic1", "topic2")),
                new HashSet<>(Arrays.asList("service1", "service2"))
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);

        // Verify the JSON contains the expected property names
        assertTrue(json.contains("\"clusterName\":\"test-cluster\""));
        assertTrue(json.contains("\"pipelineGraphConfig\":"));
        assertTrue(json.contains("\"pipelineModuleMap\":"));
        assertTrue(json.contains("\"allowedKafkaTopics\":"));
        assertTrue(json.contains("\"allowedGrpcServices\":"));
    }

    @Test
    void testLoadFromJsonFile() throws Exception {
        // Load JSON from resources
        try (InputStream is = getClass().getResourceAsStream("/pipeline-cluster-config.json")) {
            // Deserialize from JSON
            PipelineClusterConfig config = objectMapper.readValue(is, PipelineClusterConfig.class);

            // Verify the values
            assertEquals("test-cluster", config.getClusterName());
            
            // Verify PipelineGraphConfig
            assertNotNull(config.getPipelineGraphConfig());
            assertNotNull(config.getPipelineGraphConfig().getPipelines());
            assertEquals(1, config.getPipelineGraphConfig().getPipelines().size());
            
            // Verify pipeline
            PipelineConfig pipeline = config.getPipelineGraphConfig().getPipelines().get("pipeline1");
            assertNotNull(pipeline);
            assertEquals("pipeline1", pipeline.getName());
            assertEquals(2, pipeline.getPipelineSteps().size());
            
            // Verify PipelineModuleMap
            assertNotNull(config.getPipelineModuleMap());
            assertNotNull(config.getPipelineModuleMap().getAvailableModules());
            assertEquals(2, config.getPipelineModuleMap().getAvailableModules().size());
            
            // Verify module
            PipelineModuleConfiguration module = config.getPipelineModuleMap().getAvailableModules().get("test-module-1");
            assertNotNull(module);
            assertEquals("Test Module 1", module.getImplementationName());
            assertEquals("test-module-1", module.getImplementationId());
            
            // Verify allowed Kafka topics and gRPC services
            assertNotNull(config.getAllowedKafkaTopics());
            assertEquals(3, config.getAllowedKafkaTopics().size());
            assertTrue(config.getAllowedKafkaTopics().contains("test-input-topic-1"));
            assertTrue(config.getAllowedKafkaTopics().contains("intermediate-topic-1"));
            assertTrue(config.getAllowedKafkaTopics().contains("test-output-topic-1"));
            
            assertNotNull(config.getAllowedGrpcServices());
            assertEquals(2, config.getAllowedGrpcServices().size());
            assertTrue(config.getAllowedGrpcServices().contains("test-grpc-service-1"));
            assertTrue(config.getAllowedGrpcServices().contains("test-grpc-service-2"));
        }
    }
}