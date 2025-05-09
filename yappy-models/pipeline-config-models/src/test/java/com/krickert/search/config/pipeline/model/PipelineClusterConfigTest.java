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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                List.of("test-input-topic"),
                kafkaPublishTopics,
                List.of("test-grpc-service")
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
        Set<String> allowedGrpcServices = new HashSet<>(List.of("test-grpc-service"));

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
        assertEquals("test-cluster", deserialized.clusterName());

        // Verify PipelineGraphConfig
        assertNotNull(deserialized.pipelineGraphConfig());
        assertNotNull(deserialized.pipelineGraphConfig().pipelines());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());

        // Verify PipelineModuleMap
        assertNotNull(deserialized.pipelineModuleMap());
        assertNotNull(deserialized.pipelineModuleMap().availableModules());
        assertEquals(1, deserialized.pipelineModuleMap().availableModules().size());

        // Verify allowed Kafka topics and gRPC services
        assertNotNull(deserialized.allowedKafkaTopics());
        assertEquals(2, deserialized.allowedKafkaTopics().size());
        assertTrue(deserialized.allowedKafkaTopics().contains("test-input-topic"));
        assertTrue(deserialized.allowedKafkaTopics().contains("test-output-topic"));

        assertNotNull(deserialized.allowedGrpcServices());
        assertEquals(1, deserialized.allowedGrpcServices().size());
        assertTrue(deserialized.allowedGrpcServices().contains("test-grpc-service"));
    }

    @Test
    void testValidation() {
        // Test null clusterName validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                null, null, null, null, null));

        // Test blank clusterName validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "", null, null, null, null));

        // Test that other parameters can be null
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster", null, null, null, null);
        assertNull(config.pipelineGraphConfig());
        assertNull(config.pipelineModuleMap());
        assertTrue(config.allowedKafkaTopics().isEmpty());
        assertTrue(config.allowedGrpcServices().isEmpty());
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
            assertEquals("test-cluster", config.clusterName());

            // Verify PipelineGraphConfig
            assertNotNull(config.pipelineGraphConfig());
            assertNotNull(config.pipelineGraphConfig().pipelines());
            assertEquals(1, config.pipelineGraphConfig().pipelines().size());

            // Verify pipeline
            PipelineConfig pipeline = config.pipelineGraphConfig().pipelines().get("pipeline1");
            assertNotNull(pipeline);
            assertEquals("pipeline1", pipeline.name());
            assertEquals(2, pipeline.pipelineSteps().size());

            // Verify PipelineModuleMap
            assertNotNull(config.pipelineModuleMap());
            assertNotNull(config.pipelineModuleMap().availableModules());
            assertEquals(2, config.pipelineModuleMap().availableModules().size());

            // Verify module
            PipelineModuleConfiguration module = config.pipelineModuleMap().availableModules().get("test-module-1");
            assertNotNull(module);
            assertEquals("Test Module 1", module.implementationName());
            assertEquals("test-module-1", module.implementationId());

            // Verify allowed Kafka topics and gRPC services
            assertNotNull(config.allowedKafkaTopics());
            assertEquals(3, config.allowedKafkaTopics().size());
            assertTrue(config.allowedKafkaTopics().contains("test-input-topic-1"));
            assertTrue(config.allowedKafkaTopics().contains("intermediate-topic-1"));
            assertTrue(config.allowedKafkaTopics().contains("test-output-topic-1"));

            assertNotNull(config.allowedGrpcServices());
            assertEquals(2, config.allowedGrpcServices().size());
            assertTrue(config.allowedGrpcServices().contains("test-grpc-service-1"));
            assertTrue(config.allowedGrpcServices().contains("test-grpc-service-2"));
        }
    }
}
