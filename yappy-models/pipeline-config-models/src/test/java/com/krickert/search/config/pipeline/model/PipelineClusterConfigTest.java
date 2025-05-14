package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private PipelineStepConfig createSampleKafkaStep(String id, String moduleId, String next, String error) {
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"configFor\":\"" + id + "\"}");
        KafkaTransportConfig kafkaConfig = new KafkaTransportConfig(
                List.of("input-for-" + id),
                "cluster." + moduleId + "." + id + ".out",
                Map.of("acks", "1")
        );
        return new PipelineStepConfig(
                id,
                moduleId,
                customConfig,
                next != null ? List.of(next) : Collections.emptyList(),
                error != null ? List.of(error) : Collections.emptyList(),
                TransportType.KAFKA,
                kafkaConfig,
                null
        );
    }

    private PipelineStepConfig createSampleGrpcStep(String id, String moduleId, String serviceId, String next, String error) {
        JsonConfigOptions customConfig = new JsonConfigOptions("{\"configFor\":\"" + id + "\"}");
        GrpcTransportConfig grpcConfig = new GrpcTransportConfig(
                serviceId,
                Map.of("timeout", "1000ms")
        );
        return new PipelineStepConfig(
                id,
                moduleId,
                customConfig,
                next != null ? List.of(next) : Collections.emptyList(),
                error != null ? List.of(error) : Collections.emptyList(),
                TransportType.GRPC,
                null,
                grpcConfig
        );
    }
    private PipelineStepConfig createSampleInternalStep(String id, String moduleId, String next, String error) {
        return new PipelineStepConfig(
                id,
                moduleId,
                null, // No custom config
                next != null ? List.of(next) : Collections.emptyList(),
                error != null ? List.of(error) : Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null
        );
    }


    @Test
    void testSerializationDeserialization() throws Exception {
        // --- PipelineGraphConfig ---
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();

        PipelineStepConfig step1_1 = createSampleKafkaStep("p1s1", "module-k1", "p1s2", "p1err1");
        PipelineStepConfig step1_2 = createSampleGrpcStep("p1s2", "module-g1", "service-g1", null, null);
        steps1.put(step1_1.pipelineStepId(), step1_1);
        steps1.put(step1_2.pipelineStepId(), step1_2);
        PipelineConfig pipeline1 = new PipelineConfig("pipeline-alpha", steps1);
        pipelines.put(pipeline1.name(), pipeline1);
        PipelineGraphConfig pipelineGraphConfig = new PipelineGraphConfig(pipelines);

        // --- PipelineModuleMap ---
        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        SchemaReference schemaRefK1 = new SchemaReference("schema-for-module-k1", 1);
        PipelineModuleConfiguration moduleK1 = new PipelineModuleConfiguration(
                "Kafka Module K1", "module-k1", schemaRefK1);
        modules.put(moduleK1.implementationId(), moduleK1);

        SchemaReference schemaRefG1 = new SchemaReference("schema-for-module-g1", 2);
        PipelineModuleConfiguration moduleG1 = new PipelineModuleConfiguration(
                "gRPC Module G1", "module-g1", schemaRefG1);
        modules.put(moduleG1.implementationId(), moduleG1);
        PipelineModuleMap pipelineModuleMap = new PipelineModuleMap(modules);

        // --- Allowed Lists ---
        Set<String> allowedKafkaTopics = new HashSet<>(Arrays.asList("input-for-p1s1", "cluster.module-k1.p1s1.out"));
        Set<String> allowedGrpcServices = new HashSet<>(List.of("service-g1"));

        // --- PipelineClusterConfig ---
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster-001",
                pipelineGraphConfig,
                pipelineModuleMap,
                allowedKafkaTopics,
                allowedGrpcServices
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineClusterConfig JSON:\n" + json);
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        assertEquals("test-cluster-001", deserialized.clusterName());

        // Verify PipelineGraphConfig
        assertNotNull(deserialized.pipelineGraphConfig());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        PipelineConfig deserializedPipeline = deserialized.pipelineGraphConfig().pipelines().get("pipeline-alpha");
        assertNotNull(deserializedPipeline);
        assertEquals(2, deserializedPipeline.pipelineSteps().size());

        PipelineStepConfig deserializedP1S1 = deserializedPipeline.pipelineSteps().get("p1s1");
        assertNotNull(deserializedP1S1);
        assertEquals(TransportType.KAFKA, deserializedP1S1.transportType());
        assertNotNull(deserializedP1S1.kafkaConfig());
        assertEquals("input-for-p1s1", deserializedP1S1.kafkaConfig().listenTopics().getFirst());
        assertEquals(List.of("p1s2"), deserializedP1S1.nextSteps());

        // Verify PipelineModuleMap
        assertNotNull(deserialized.pipelineModuleMap());
        assertEquals(2, deserialized.pipelineModuleMap().availableModules().size());
        assertTrue(deserialized.pipelineModuleMap().availableModules().containsKey("module-k1"));
        assertEquals("schema-for-module-g1", deserialized.pipelineModuleMap().availableModules().get("module-g1").customConfigSchemaReference().subject());


        // Verify allowed Kafka topics and gRPC services
        assertEquals(allowedKafkaTopics, deserialized.allowedKafkaTopics());
        assertEquals(allowedGrpcServices, deserialized.allowedGrpcServices());
    }

    @Test
    void testValidation() {
        Exception eNullName = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                null, null, null, null, null));
        assertTrue(eNullName.getMessage().contains("clusterName cannot be null or blank"));

        Exception eBlankName = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                " ", null, null, null, null));
        assertTrue(eBlankName.getMessage().contains("clusterName cannot be null or blank"));


        PipelineClusterConfig configWithNulls = new PipelineClusterConfig(
                "test-cluster-with-nulls", null, null, null, null);
        assertNull(configWithNulls.pipelineGraphConfig()); // Allowed to be null
        assertNull(configWithNulls.pipelineModuleMap());   // Allowed to be null

        // Constructor should default null sets to empty sets
        assertNotNull(configWithNulls.allowedKafkaTopics());
        assertTrue(configWithNulls.allowedKafkaTopics().isEmpty());
        assertNotNull(configWithNulls.allowedGrpcServices());
        assertTrue(configWithNulls.allowedGrpcServices().isEmpty());

        // Test validation for elements within allowedKafkaTopics/allowedGrpcServices
        // (Assuming the canonical constructor of PipelineClusterConfig handles this)
        Set<String> topicsWithNullEl = new HashSet<>();
        topicsWithNullEl.add(null);
        Exception eTopicNull = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "c1", null, null, topicsWithNullEl, null));
        assertTrue(eTopicNull.getMessage().contains("allowedKafkaTopics cannot contain null or blank strings"));


        Set<String> servicesWithBlankEl = new HashSet<>();
        servicesWithBlankEl.add("");
        Exception eServiceBlank = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "c1", null, null, null, servicesWithBlankEl));
        assertTrue(eServiceBlank.getMessage().contains("allowedGrpcServices cannot contain null or blank strings"));
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        PipelineClusterConfig config = new PipelineClusterConfig(
                "json-prop-cluster",
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                Set.of("topicA"),
                Set.of("serviceX")
        );
        String json = objectMapper.writeValueAsString(config);
        System.out.println("JSON for testJsonPropertyNames() output:\n" + json);

        assertTrue(json.contains("\"clusterName\"") && json.contains("\"json-prop-cluster\""),
                "clusterName key/value not found as expected. JSON: " + json);

        // For pipelineGraphConfig
        assertTrue(json.contains("\"pipelineGraphConfig\""), "pipelineGraphConfig key missing. JSON: " + json);
        assertTrue(json.contains("\"pipelines\""), "nested 'pipelines' key missing in pipelineGraphConfig. JSON: " + json);
        // ADD (?s) for DOTALL mode to allow . to match newlines
        assertTrue(json.matches("(?s).*\"pipelines\"\\s*:\\s*\\{\\s*\\}.*"),  // <--- MODIFIED REGEX
                "'pipelines' was not an empty object {{}}. JSON: " + json);


        // For pipelineModuleMap
        assertTrue(json.contains("\"pipelineModuleMap\""), "pipelineModuleMap key missing. JSON: " + json);
        assertTrue(json.contains("\"availableModules\""), "nested 'availableModules' key missing in pipelineModuleMap. JSON: " + json);
        // ADD (?s) for DOTALL mode
        assertTrue(json.matches("(?s).*\"availableModules\"\\s*:\\s*\\{\\s*\\}.*"), // <--- MODIFIED REGEX
                "'availableModules' was not an empty object {{}}. JSON: " + json);


        assertTrue(json.contains("\"allowedKafkaTopics\"") && json.contains("\"topicA\""),
                "allowedKafkaTopics key/value not found as expected. JSON: " + json);
        assertTrue(json.contains("\"allowedGrpcServices\"") && json.contains("\"serviceX\""),
                "allowedGrpcServices key/value not found as expected. JSON: " + json);
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        // Requires "pipeline-cluster-config-new-model.json" in src/test/resources
        // This JSON file must use the new PipelineStepConfig structure.
        // Example JSON structure:
        // {
        //   "clusterName": "loaded-cluster",
        //   "pipelineGraphConfig": {
        //     "pipelines": {
        //       "dataPipeline1": {
        //         "name": "dataPipeline1",
        //         "pipelineSteps": {
        //           "kafkaIngest": {
        //             "pipelineStepId": "kafkaIngest",
        //             "pipelineImplementationId": "ingestMod",
        //             "transportType": "KAFKA",
        //             "kafkaConfig": {"listenTopics": ["rawInput"], "publishTopicPattern": "p1.ingest.out"},
        //             "nextSteps": ["processInternal"]
        //           },
        //           "processInternal": {
        //             "pipelineStepId": "processInternal",
        //             "pipelineImplementationId": "procMod",
        //             "transportType": "INTERNAL"
        //           }
        //         }
        //       }
        //     }
        //   },
        //   "pipelineModuleMap": {
        //     "availableModules": {
        //       "ingestMod": {"implementationName":"Ingest Module", "implementationId":"ingestMod", "customConfigSchemaReference":{"subject":"ingestSchema","version":1}},
        //       "procMod": {"implementationName":"Processing Module", "implementationId":"procMod", "customConfigSchemaReference":{"subject":"procSchema","version":1}}
        //     }
        //   },
        //   "allowedKafkaTopics": ["rawInput", "p1.ingest.out"],
        //   "allowedGrpcServices": []
        // }
        try (InputStream is = getClass().getResourceAsStream("/pipeline-cluster-config-new-model.json")) {
            assertNotNull(is, "Could not load resource /pipeline-cluster-config-new-model.json. Please create it with the new model.");
            PipelineClusterConfig config = objectMapper.readValue(is, PipelineClusterConfig.class);

            assertEquals("loaded-cluster", config.clusterName());
            assertNotNull(config.pipelineGraphConfig());
            assertEquals(1, config.pipelineGraphConfig().pipelines().size());
            PipelineConfig p1 = config.pipelineGraphConfig().pipelines().get("dataPipeline1");
            assertNotNull(p1);
            PipelineStepConfig ingestStep = p1.pipelineSteps().get("kafkaIngest");
            assertNotNull(ingestStep);
            assertEquals(TransportType.KAFKA, ingestStep.transportType());
            assertNotNull(ingestStep.kafkaConfig());
            assertEquals("rawInput", ingestStep.kafkaConfig().listenTopics().getFirst());

            assertNotNull(config.pipelineModuleMap());
            assertTrue(config.pipelineModuleMap().availableModules().containsKey("ingestMod"));
            assertEquals(Set.of("rawInput", "p1.ingest.out"), config.allowedKafkaTopics());
            assertTrue(config.allowedGrpcServices().isEmpty());
        }
    }

    @Test
    void testImmutabilityOfCollections() {
        // Setup with new PipelineStepConfig
        PipelineStepConfig step = createSampleInternalStep("s1", "m1", null, null);
        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.pipelineStepId(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));
        PipelineModuleConfiguration module = new PipelineModuleConfiguration("M1", "m1", new SchemaReference("ref", 1));
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(module.implementationId(), module));
        Set<String> topics = new HashSet<>(List.of("topicA"));
        Set<String> services = new HashSet<>(List.of("serviceA"));

        PipelineClusterConfig config = new PipelineClusterConfig(
                "immutable-cluster", graphConfig, moduleMap, topics, services);

        assertThrows(UnsupportedOperationException.class, () -> config.allowedKafkaTopics().add("newTopic"));
        assertThrows(UnsupportedOperationException.class, () -> config.allowedGrpcServices().add("newService"));
        assertNotNull(config.pipelineModuleMap()); // Should not be null if constructed with non-null
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineModuleMap().availableModules().put("m2", null));
        assertNotNull(config.pipelineGraphConfig()); // Should not be null if constructed with non-null
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineGraphConfig().pipelines().put("p2", null));

        PipelineConfig p1 = config.pipelineGraphConfig().pipelines().get("p1");
        assertNotNull(p1);
        assertThrows(UnsupportedOperationException.class, () -> p1.pipelineSteps().put("s2", null));

        // Immutability of lists within a PipelineStepConfig's transport config would be tested
        // in PipelineStepConfigTest itself, assuming Map.copyOf makes shallow copies.
        // Here we test collections directly held or deeply held by PipelineClusterConfig.
    }

    @Test
    void testEqualityAndHashCode_WithNewStepModel() {
        // Create steps using the new model
        PipelineStepConfig stepA1 = createSampleKafkaStep("s1", "m1", "s2", null);
        PipelineStepConfig stepA2 = createSampleGrpcStep("s2", "m-grpc", "svc-grpc", null, null);

        PipelineStepConfig stepB1 = createSampleKafkaStep("s1", "m1", "s2", null); // Identical to stepA1
        PipelineStepConfig stepB2 = createSampleGrpcStep("s2", "m-grpc", "svc-grpc", null, null); // Identical to stepA2

        PipelineGraphConfig graph1 = new PipelineGraphConfig(
                Map.of("p1", new PipelineConfig("p1", Map.of(stepA1.pipelineStepId(), stepA1, stepA2.pipelineStepId(), stepA2)))
        );
        PipelineGraphConfig graph2 = new PipelineGraphConfig( // Identical to graph1
                Map.of("p1", new PipelineConfig("p1", Map.of(stepB1.pipelineStepId(), stepB1, stepB2.pipelineStepId(), stepB2)))
        );
        // Create a different graph for inequality test
        PipelineStepConfig stepC1 = createSampleInternalStep("s_other", "m_other", null, null);
        PipelineGraphConfig graph3 = new PipelineGraphConfig(
                Map.of("p_other", new PipelineConfig("p_other", Map.of(stepC1.pipelineStepId(), stepC1)))
        );


        PipelineModuleMap modules1 = new PipelineModuleMap(
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc",1)))
        );
        PipelineModuleMap modules2 = new PipelineModuleMap( // Identical to modules1
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc",1)))
        );


        PipelineClusterConfig config1 = new PipelineClusterConfig("clusterA", graph1, modules1, Set.of("t1"), Set.of("g1"));
        PipelineClusterConfig config2 = new PipelineClusterConfig("clusterA", graph2, modules2, Set.of("t1"), Set.of("g1")); // Identical
        PipelineClusterConfig config3_diff_graph = new PipelineClusterConfig("clusterA", graph3, modules1, Set.of("t1"), Set.of("g1"));


        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3_diff_graph);
    }
}