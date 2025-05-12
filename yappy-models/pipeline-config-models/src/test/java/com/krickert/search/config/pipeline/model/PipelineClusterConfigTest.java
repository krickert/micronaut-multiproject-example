package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                List.of("test-grpc-service"),
                List.of("next-step-1"),      // nextSteps
                List.of("error-step-1")       // errorSteps
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
        PipelineConfig deserializedPipeline = deserialized.pipelineGraphConfig().pipelines().get("test-pipeline");
        assertNotNull(deserializedPipeline);
        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("test-step");
        assertNotNull(deserializedStep);
        assertEquals("next-step-1", deserializedStep.nextSteps().getFirst());
        assertEquals("error-step-1", deserializedStep.errorSteps().getFirst());


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
                null, null, null, null, null), "clusterName cannot be null");

        // Test blank clusterName validation
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "", null, null, null, null), "clusterName cannot be blank");

        // Test that other parameters can be null and result in empty/null collections/maps
        PipelineClusterConfig configWithNulls = new PipelineClusterConfig(
                "test-cluster-with-nulls", null, null, null, null);
        assertNull(configWithNulls.pipelineGraphConfig(), "pipelineGraphConfig should be null if passed as null");
        assertNull(configWithNulls.pipelineModuleMap(), "pipelineModuleMap should be null if passed as null");

        assertNotNull(configWithNulls.allowedKafkaTopics(), "allowedKafkaTopics should be empty, not null");
        assertTrue(configWithNulls.allowedKafkaTopics().isEmpty(), "allowedKafkaTopics should be empty if passed as null");

        assertNotNull(configWithNulls.allowedGrpcServices(), "allowedGrpcServices should be empty, not null");
        assertTrue(configWithNulls.allowedGrpcServices().isEmpty(), "allowedGrpcServices should be empty if passed as null");

        // Test validation for elements within allowedKafkaTopics
        Set<String> topicsWithNull = new HashSet<>();
        topicsWithNull.add("valid");
        topicsWithNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                        "test-cluster", null, null, topicsWithNull, null),
                "allowedKafkaTopics should not allow null elements");

        Set<String> topicsWithBlank = new HashSet<>();
        topicsWithBlank.add("valid");
        topicsWithBlank.add("");
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                        "test-cluster", null, null, topicsWithBlank, null),
                "allowedKafkaTopics should not allow blank elements");

        // Test validation for elements within allowedGrpcServices
        Set<String> servicesWithNull = new HashSet<>();
        servicesWithNull.add("valid");
        servicesWithNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                        "test-cluster", null, null, null, servicesWithNull),
                "allowedGrpcServices should not allow null elements");

        Set<String> servicesWithBlank = new HashSet<>();
        servicesWithBlank.add("valid");
        servicesWithBlank.add("");
        assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                        "test-cluster", null, null, null, servicesWithBlank),
                "allowedGrpcServices should not allow blank elements");
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        // Create a simple PipelineClusterConfig instance
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                new PipelineGraphConfig(new HashMap<>()), // Empty graph
                new PipelineModuleMap(new HashMap<>()),   // Empty module map
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
            assertNotNull(is, "Could not load resource /pipeline-cluster-config.json");
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
            // Example: Verify nextSteps/errorSteps for a step if present in JSON
            PipelineStepConfig step1FromJson = pipeline.pipelineSteps().get("step1");
            assertNotNull(step1FromJson);
            assertNotNull(step1FromJson.nextSteps()); // Should be at least an empty list
            assertNotNull(step1FromJson.errorSteps()); // Should be at least an empty list
            // if (step1FromJson.nextSteps().contains("step2")) { /* assertion */ }


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

    // --- New Tests ---

    @Test
    void testSerializationWithMinimalValidConfig() throws Exception {
        PipelineClusterConfig config = new PipelineClusterConfig(
                "minimal-cluster",
                null, // No pipeline graph
                null, // No module map
                Collections.emptySet(), // No Kafka topics
                Collections.emptySet()  // No gRPC services
        );

        String json = objectMapper.writeValueAsString(config);
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        assertEquals("minimal-cluster", deserialized.clusterName());
        assertNull(deserialized.pipelineGraphConfig());
        assertNull(deserialized.pipelineModuleMap());
        assertNotNull(deserialized.allowedKafkaTopics());
        assertTrue(deserialized.allowedKafkaTopics().isEmpty());
        assertNotNull(deserialized.allowedGrpcServices());
        assertTrue(deserialized.allowedGrpcServices().isEmpty());
    }

    @Test
    void testImmutabilityOfCollections() {
        Set<String> initialTopics = new HashSet<>(List.of("topicA"));
        Set<String> initialServices = new HashSet<>(List.of("serviceA"));

        Map<String, PipelineModuleConfiguration> initialModules = new HashMap<>();
        initialModules.put("m1", new PipelineModuleConfiguration("M1", "m1", null));
        PipelineModuleMap moduleMap = new PipelineModuleMap(initialModules);


        Map<String, PipelineStepConfig> initialSteps = new HashMap<>();
        PipelineStepConfig step = new PipelineStepConfig("s1", "m1", null, null, null, null, null, null);
        initialSteps.put("s1", step);
        Map<String, PipelineConfig> initialPipelines = new HashMap<>();
        initialPipelines.put("p1", new PipelineConfig("p1", initialSteps));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(initialPipelines);


        PipelineClusterConfig config = new PipelineClusterConfig(
                "immutable-cluster",
                graphConfig,
                moduleMap,
                initialTopics,
                initialServices
        );

        // Test top-level collections
        assertThrows(UnsupportedOperationException.class, () -> config.allowedKafkaTopics().add("newTopic"),
                "allowedKafkaTopics should be unmodifiable");
        assertThrows(UnsupportedOperationException.class, () -> config.allowedGrpcServices().add("newService"),
                "allowedGrpcServices should be unmodifiable");

        // Test nested collections
        assertNotNull(config.pipelineModuleMap());
        assertNotNull(config.pipelineModuleMap().availableModules());
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineModuleMap().availableModules().put("m2", null),
                "availableModules in PipelineModuleMap should be unmodifiable");

        assertNotNull(config.pipelineGraphConfig());
        assertNotNull(config.pipelineGraphConfig().pipelines());
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineGraphConfig().pipelines().put("p2", null),
                "pipelines in PipelineGraphConfig should be unmodifiable");

        PipelineConfig p1 = config.pipelineGraphConfig().pipelines().get("p1");
        assertNotNull(p1);
        assertNotNull(p1.pipelineSteps());
        assertThrows(UnsupportedOperationException.class, () -> p1.pipelineSteps().put("s2", null),
                "pipelineSteps in PipelineConfig should be unmodifiable");

        PipelineStepConfig s1 = p1.pipelineSteps().get("s1");
        assertNotNull(s1);
        assertNotNull(s1.kafkaListenTopics()); // Assuming constructor makes it at least empty
        assertThrows(UnsupportedOperationException.class, () -> s1.kafkaListenTopics().add("newListenTopic"),
                "kafkaListenTopics in PipelineStepConfig should be unmodifiable");
    }

    @Test
    void testEqualityAndHashCode() {
        PipelineGraphConfig graph1 = new PipelineGraphConfig(
                Map.of("p1", new PipelineConfig("p1",
                        Map.of("s1", new PipelineStepConfig("s1", "m1", null, List.of("in"), null, null, List.of("s2"), null))
                ))
        );
        PipelineGraphConfig graph2 = new PipelineGraphConfig(
                Map.of("p1", new PipelineConfig("p1",
                        Map.of("s1", new PipelineStepConfig("s1", "m1", null, List.of("in"), null, null, List.of("s2"), null))
                ))
        );
        PipelineGraphConfig graph3 = new PipelineGraphConfig(
                Map.of("p2", new PipelineConfig("p2",
                        Map.of("s1", new PipelineStepConfig("s1", "m1", null, List.of("in"), null, null, List.of("s2"), null))
                ))
        );

        PipelineModuleMap modules1 = new PipelineModuleMap(
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)))
        );
        PipelineModuleMap modules2 = new PipelineModuleMap(
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)))
        );
        PipelineModuleMap modules3 = new PipelineModuleMap(
                Map.of("m2", new PipelineModuleConfiguration("Mod2", "m2", new SchemaReference("schema", 2)))
        );

        Set<String> topics1 = Set.of("t1", "t2");
        Set<String> topics2 = Set.of("t1", "t2");
        Set<String> topics3 = Set.of("t3");

        Set<String> services1 = Set.of("g1");
        Set<String> services2 = Set.of("g1");
        Set<String> services3 = Set.of("g2");


        PipelineClusterConfig config1 = new PipelineClusterConfig("clusterA", graph1, modules1, topics1, services1);
        PipelineClusterConfig config2 = new PipelineClusterConfig("clusterA", graph2, modules2, topics2, services2); // Identical to config1
        PipelineClusterConfig config3 = new PipelineClusterConfig("clusterB", graph1, modules1, topics1, services1); // Different clusterName
        PipelineClusterConfig config4 = new PipelineClusterConfig("clusterA", graph3, modules1, topics1, services1); // Different graph
        PipelineClusterConfig config5 = new PipelineClusterConfig("clusterA", graph1, modules3, topics1, services1); // Different modules
        PipelineClusterConfig config6 = new PipelineClusterConfig("clusterA", graph1, modules1, topics3, services1); // Different topics
        PipelineClusterConfig config7 = new PipelineClusterConfig("clusterA", graph1, modules1, topics1, services3); // Different services

        // Test equality
        assertEquals(config1, config2, "Identical configs should be equal");
        assertEquals(config1.hashCode(), config2.hashCode(), "Hashcodes of identical configs should be equal");

        assertNotEquals(config1, config3, "Configs with different clusterName should not be equal");
        assertNotEquals(config1, config4, "Configs with different pipelineGraphConfig should not be equal");
        assertNotEquals(config1, config5, "Configs with different pipelineModuleMap should not be equal");
        assertNotEquals(config1, config6, "Configs with different allowedKafkaTopics should not be equal");
        assertNotEquals(config1, config7, "Configs with different allowedGrpcServices should not be equal");

        // Test with nulls (assuming canonical constructor handles them consistently, e.g., to empty collections)
        PipelineClusterConfig configWithNullGraph1 = new PipelineClusterConfig("clusterC", null, modules1, topics1, services1);
        PipelineClusterConfig configWithNullGraph2 = new PipelineClusterConfig("clusterC", null, modules1, topics1, services1);
        PipelineClusterConfig configWithNonNullGraph = new PipelineClusterConfig("clusterC", graph1, modules1, topics1, services1);

        assertEquals(configWithNullGraph1, configWithNullGraph2, "Configs with null graphs should be equal if other fields match");
        assertNotEquals(configWithNullGraph1, configWithNonNullGraph, "Config with null graph should not equal config with non-null graph");
    }

    @Test
    void testSerializationWithComplexNestedStructure() throws Exception {
        // Modules
        PipelineModuleConfiguration moduleA = new PipelineModuleConfiguration("ModuleA", "mod-a", new SchemaReference("schema-a", 1));
        PipelineModuleConfiguration moduleB = new PipelineModuleConfiguration("ModuleB", "mod-b", new SchemaReference("schema-b", 1));
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of("mod-a", moduleA, "mod-b", moduleB));

        // Steps for Pipeline 1
        PipelineStepConfig p1s1 = new PipelineStepConfig("p1s1", "mod-a", new JsonConfigOptions("{\"p1s1_config\":true}"),
                List.of("input-topic-p1"), List.of(new KafkaPublishTopic("intermediate-p1-p2")), null,
                List.of("p1s2"), List.of("p1-error-handler"));
        PipelineStepConfig p1s2 = new PipelineStepConfig("p1s2", "mod-b", null,
                List.of("intermediate-p1-p2"), List.of(new KafkaPublishTopic("output-topic-p1")), null,
                null, null);
        PipelineStepConfig p1Error = new PipelineStepConfig("p1-error-handler", "mod-a", null, null, null, null, null, null);

        // Pipeline 1
        PipelineConfig pipeline1 = new PipelineConfig("pipelineOne", Map.of(
                p1s1.pipelineStepId(), p1s1,
                p1s2.pipelineStepId(), p1s2,
                p1Error.pipelineStepId(), p1Error
        ));

        // Steps for Pipeline 2
        PipelineStepConfig p2s1 = new PipelineStepConfig("p2s1", "mod-b", new JsonConfigOptions("{\"val\":100}"),
                List.of("input-topic-p2"), List.of(new KafkaPublishTopic("output-topic-p2")), List.of("grpc-service-x"),
                null, null);

        // Pipeline 2
        PipelineConfig pipeline2 = new PipelineConfig("pipelineTwo", Map.of(p2s1.pipelineStepId(), p2s1));

        // Graph
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(
                pipeline1.name(), pipeline1,
                pipeline2.name(), pipeline2
        ));

        // Cluster Config
        PipelineClusterConfig originalConfig = new PipelineClusterConfig(
                "complex-cluster",
                graphConfig,
                moduleMap,
                Set.of("input-topic-p1", "input-topic-p2", "output-topic-p1", "output-topic-p2"),
                Set.of("grpc-service-x")
        );

        String json = objectMapper.writeValueAsString(originalConfig);
        PipelineClusterConfig deserializedConfig = objectMapper.readValue(json, PipelineClusterConfig.class);

        // Perform deep equality check (or selective checks)
        assertEquals(originalConfig, deserializedConfig, "Deserialized complex config should be equal to the original");
        assertEquals(originalConfig.hashCode(), deserializedConfig.hashCode(), "Hashcodes of complex configs should match");

        // Selective detailed checks
        assertEquals(2, deserializedConfig.pipelineGraphConfig().pipelines().size());
        PipelineConfig dPipeline1 = deserializedConfig.pipelineGraphConfig().pipelines().get("pipelineOne");
        assertNotNull(dPipeline1);
        assertEquals(3, dPipeline1.pipelineSteps().size());
        PipelineStepConfig dP1s1 = dPipeline1.pipelineSteps().get("p1s1");
        assertNotNull(dP1s1);
        assertEquals("mod-a", dP1s1.pipelineImplementationId());
        assertEquals(1, dP1s1.nextSteps().size());
        assertEquals("p1s2", dP1s1.nextSteps().getFirst());
        assertEquals(1, dP1s1.errorSteps().size());
        assertEquals("p1-error-handler", dP1s1.errorSteps().getFirst());

        PipelineModuleConfiguration dModuleA = deserializedConfig.pipelineModuleMap().availableModules().get("mod-a");
        assertNotNull(dModuleA);
        assertEquals("schema-a", dModuleA.customConfigSchemaReference().subject());
    }
}