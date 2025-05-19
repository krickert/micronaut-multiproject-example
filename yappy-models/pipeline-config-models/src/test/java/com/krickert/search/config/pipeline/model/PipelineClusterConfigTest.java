package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigJson;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigObjects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PipelineClusterConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = PipelineConfigTestUtils.createObjectMapper();
    }

    // Helper to create JsonConfigOptions from a JSON string
    private PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(String jsonString) throws com.fasterxml.jackson.core.JsonProcessingException {
        return PipelineConfigTestUtils.createJsonConfigOptions(jsonString);
    }

    // --- Refactored Helper Methods for creating new PipelineStepConfig records ---
    private PipelineStepConfig createSampleStep(
            String stepName,
            String moduleImplementationId, // Used for ProcessorInfo
            TransportType processorNature, // KAFKA/GRPC implies grpcServiceName, INTERNAL implies internalProcessorBeanName
            String customConfigJson,
            List<String> nextStepTargetNames,    // For the "default" outputs
            List<String> errorStepTargetNames,   // For the "onError" outputs
            TransportType defaultOutputTransportType, // Transport for the "default" output
            Object defaultOutputTransportConfig, // KafkaTransportConfig or GrpcTransportConfig for "default" output
            StepType stepType // The new StepType enum (PIPELINE, SINK, INITIAL_PIPELINE)
    ) throws com.fasterxml.jackson.core.JsonProcessingException {

        PipelineStepConfig.ProcessorInfo processorInfo;
        if (processorNature == TransportType.KAFKA || processorNature == TransportType.GRPC) {
            processorInfo = new PipelineStepConfig.ProcessorInfo(moduleImplementationId, null);
        } else { // INTERNAL
            processorInfo = new PipelineStepConfig.ProcessorInfo(null, moduleImplementationId);
        }

        PipelineStepConfig.JsonConfigOptions configOptions = createJsonConfigOptions(customConfigJson);

        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        if (nextStepTargetNames != null) {
            for (String nextTarget : nextStepTargetNames) {
                KafkaTransportConfig outKafkaCfg = null;
                GrpcTransportConfig outGrpcCfg = null;
                if (defaultOutputTransportType == TransportType.KAFKA && defaultOutputTransportConfig instanceof KafkaTransportConfig) {
                    outKafkaCfg = (KafkaTransportConfig) defaultOutputTransportConfig;
                } else if (defaultOutputTransportType == TransportType.GRPC && defaultOutputTransportConfig instanceof GrpcTransportConfig) {
                    outGrpcCfg = (GrpcTransportConfig) defaultOutputTransportConfig;
                } else if (defaultOutputTransportType == TransportType.INTERNAL && defaultOutputTransportConfig == null) {
                    // Correct for internal
                } else if (defaultOutputTransportConfig != null) { // Should not happen if arguments are correct
                    throw new IllegalArgumentException("Mismatched output transport config for type: " + defaultOutputTransportType + " for target " + nextTarget);
                }
                // Ensure unique keys if multiple next steps with same logical name (e.g. "default")
                // For simplicity, this helper uses a generic "default" or "onError" key pattern.
                // If actual nextSteps were List.of("t1", "t2"), we'd need unique keys like "output_t1", "output_t2"
                String outputKey = "default_" + nextTarget.replaceAll("[^a-zA-Z0-9.-]", "_");
                outputs.put(outputKey,
                        new PipelineStepConfig.OutputTarget(nextTarget, defaultOutputTransportType, outGrpcCfg, outKafkaCfg)
                );
            }
        }

        if (errorStepTargetNames != null) {
            for (String errorTarget : errorStepTargetNames) {
                String outputKey = "onError_" + errorTarget.replaceAll("[^a-zA-Z0-9.-]", "_");
                outputs.put(outputKey,
                        new PipelineStepConfig.OutputTarget(errorTarget, TransportType.INTERNAL, null, null) // Default error paths to INTERNAL
                );
            }
        }

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = Collections.emptyList();
        if (processorNature == TransportType.KAFKA) {
            // If this is a Kafka processor, add a sample KafkaInputDefinition
            kafkaInputs = List.of(
                    new KafkaInputDefinition(
                            List.of("input-topic-for-" + stepName),
                            "consumer-group-for-" + stepName,
                            Map.of("auto.offset.reset", "earliest")
                    )
            );
        }

        return new PipelineStepConfig(
                stepName,
                stepType == null ? com.krickert.search.config.pipeline.model.StepType.PIPELINE : stepType,
                "Description for " + stepName,
                "schema-for-" + moduleImplementationId, // customConfigSchemaId
                configOptions,
                kafkaInputs,
                outputs,
                0, 1000L, 30000L, 2.0, null, // Default retry/timeout
                processorInfo
        );
    }


    @Test
    void testSerializationDeserialization() throws Exception {
        // Use the sample pipeline cluster config from the test utilities
        PipelineClusterConfig config = SamplePipelineConfigObjects.createSearchIndexingPipelineClusterConfig();

        // Serialize to JSON using the test utilities
        String json = PipelineConfigTestUtils.toJson(config);
        System.out.println("Serialized PipelineClusterConfig JSON (New Model):\n" + json);

        // Deserialize back to object using the test utilities
        PipelineClusterConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineClusterConfig.class);

        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);

        // Verify specific properties
        assertEquals("search-indexing-cluster", deserialized.clusterName());
        assertEquals("search-indexing-pipeline", deserialized.defaultPipelineName());

        // Verify pipeline graph config
        assertNotNull(deserialized.pipelineGraphConfig());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());

        // Verify pipeline
        PipelineConfig deserializedPipeline = deserialized.pipelineGraphConfig().pipelines().get("search-indexing-pipeline");
        assertNotNull(deserializedPipeline);
        assertEquals(5, deserializedPipeline.pipelineSteps().size());

        // Verify file connector step
        PipelineStepConfig fileConnectorStep = deserializedPipeline.pipelineSteps().get("file-connector");
        assertNotNull(fileConnectorStep);
        assertEquals(StepType.INITIAL_PIPELINE, fileConnectorStep.stepType());
        assertEquals("file-connector-service", fileConnectorStep.processorInfo().grpcServiceName());

        // Verify document parser step
        PipelineStepConfig documentParserStep = deserializedPipeline.pipelineSteps().get("document-parser");
        assertNotNull(documentParserStep);
        assertEquals(StepType.PIPELINE, documentParserStep.stepType());
        assertEquals("document-parser-service", documentParserStep.processorInfo().grpcServiceName());

        // Verify module map
        assertNotNull(deserialized.pipelineModuleMap());
        assertEquals(5, deserialized.pipelineModuleMap().availableModules().size());

        // Verify allowed topics and services
        assertTrue(deserialized.allowedKafkaTopics().contains("search.files.incoming"));
        assertTrue(deserialized.allowedGrpcServices().contains("file-connector-service"));
    }

    @Test
    void testValidation_PipelineClusterConfigConstructor() {
        // Test null clusterName validation (direct constructor call)
        Exception eNullName = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                null, null, null, null, null, null));
        assertTrue(eNullName.getMessage().contains("clusterName cannot be null"));

        // Test blank clusterName validation (direct constructor call)
        Exception eBlankName = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                " ", null, null, null, null, null));
        assertTrue(eBlankName.getMessage().contains("PipelineClusterConfig clusterName cannot be null or blank"));

        PipelineClusterConfig configWithNulls = new PipelineClusterConfig(
                "test-cluster-with-nulls", null, null, null, null, null);
        assertNull(configWithNulls.pipelineGraphConfig());
        assertNull(configWithNulls.pipelineModuleMap());
        assertNotNull(configWithNulls.allowedKafkaTopics());
        assertTrue(configWithNulls.allowedKafkaTopics().isEmpty());
        assertNotNull(configWithNulls.allowedGrpcServices());
        assertTrue(configWithNulls.allowedGrpcServices().isEmpty());

        Set<String> topicsWithNullEl = new HashSet<>();
        topicsWithNullEl.add(null);
        // When calling constructor directly, expect the direct exception from validateNoNullOrBlankElements
        Exception eTopicNull = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "c1", null, null, null, topicsWithNullEl, null));
        assertTrue(eTopicNull.getMessage().contains("allowedKafkaTopics cannot contain null or blank strings"));

        Set<String> servicesWithBlankEl = new HashSet<>();
        servicesWithBlankEl.add("   ");
        // When calling constructor directly, expect the direct exception
        Exception eServiceBlank = assertThrows(IllegalArgumentException.class, () -> new PipelineClusterConfig(
                "c1", null, null, null, null, servicesWithBlankEl));
        assertTrue(eServiceBlank.getMessage().contains("allowedGrpcServices cannot contain null or blank strings"));
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        PipelineClusterConfig config = new PipelineClusterConfig(
                "json-prop-cluster",
                new PipelineGraphConfig(Collections.emptyMap()),
                new PipelineModuleMap(Collections.emptyMap()),
                "default-pipeline-name",
                Set.of("topicA"),
                Set.of("serviceX")
        );
        String json = objectMapper.writeValueAsString(config);
        System.out.println("JSON for PipelineClusterConfigTest.testJsonPropertyNames() output:\n" + json);

        assertTrue(json.contains("\"clusterName\" : \"json-prop-cluster\""));
        assertTrue(json.contains("\"pipelineGraphConfig\" : {"));
        assertTrue(json.contains("\"pipelines\" : { }"));
        assertTrue(json.contains("\"pipelineModuleMap\" : {"));
        assertTrue(json.contains("\"availableModules\" : { }"));
        assertTrue(json.contains("\"defaultPipelineName\" : \"default-pipeline-name\""));
        assertTrue(json.contains("\"allowedKafkaTopics\" : [ \"topicA\" ]"));
        assertTrue(json.contains("\"allowedGrpcServices\" : [ \"serviceX\" ]"));
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        // Get the search indexing pipeline JSON from the test utilities
        String jsonToLoad = SamplePipelineConfigJson.getSearchIndexingPipelineJson();

        // Deserialize the JSON to a PipelineClusterConfig object
        PipelineClusterConfig config = PipelineConfigTestUtils.fromJson(jsonToLoad, PipelineClusterConfig.class);

        // Verify the cluster properties
        assertEquals("search-indexing-cluster", config.clusterName());
        assertEquals("search-indexing-pipeline", config.defaultPipelineName());

        // Verify the pipeline graph config
        assertNotNull(config.pipelineGraphConfig());
        assertEquals(1, config.pipelineGraphConfig().pipelines().size());

        // Verify the pipeline
        PipelineConfig pipeline = config.pipelineGraphConfig().pipelines().get("search-indexing-pipeline");
        assertNotNull(pipeline);
        assertEquals("search-indexing-pipeline", pipeline.name());
        assertEquals(5, pipeline.pipelineSteps().size());

        // Verify the file connector step
        PipelineStepConfig fileConnectorStep = pipeline.pipelineSteps().get("file-connector");
        assertNotNull(fileConnectorStep);
        assertEquals("file-connector", fileConnectorStep.stepName());
        assertEquals(StepType.INITIAL_PIPELINE, fileConnectorStep.stepType());
        assertEquals("file-connector-service", fileConnectorStep.processorInfo().grpcServiceName());
        assertEquals("search.files.incoming", fileConnectorStep.outputs().get("default").kafkaTransport().topic());

        // Verify the document parser step
        PipelineStepConfig documentParserStep = pipeline.pipelineSteps().get("document-parser");
        assertNotNull(documentParserStep);
        assertEquals("document-parser-service", documentParserStep.processorInfo().grpcServiceName());

        // Verify the module map
        assertNotNull(config.pipelineModuleMap());
        assertEquals(5, config.pipelineModuleMap().availableModules().size());
        assertTrue(config.pipelineModuleMap().availableModules().containsKey("document-parser-service"));
        assertEquals("document-parser-schema", config.pipelineModuleMap().availableModules().get("document-parser-service").customConfigSchemaReference().subject());

        // Verify the allowed topics and services
        assertTrue(config.allowedKafkaTopics().contains("search.files.incoming"));
        assertTrue(config.allowedGrpcServices().contains("file-connector-service"));
    }

    @Test
    void testImmutabilityOfCollections() throws Exception {
        PipelineStepConfig.ProcessorInfo pi = new PipelineStepConfig.ProcessorInfo(null, "m1-bean");
        PipelineStepConfig step = new PipelineStepConfig(
                "s1", StepType.PIPELINE, null, null, null, Collections.emptyMap(),
                0, 1L, 1L, 1.0, 1L, pi);

        PipelineConfig pipeline = new PipelineConfig("p1", Map.of(step.stepName(), step));
        PipelineGraphConfig graphConfig = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));

        PipelineModuleConfiguration module = new PipelineModuleConfiguration("M1", "m1", new SchemaReference("ref", 1));
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(module.implementationId(), module));

        Set<String> topics = new HashSet<>(List.of("topicA"));
        Set<String> services = new HashSet<>(List.of("serviceA"));

        PipelineClusterConfig config = new PipelineClusterConfig(
                "immutable-cluster", graphConfig, moduleMap, null, topics, services);

        assertThrows(UnsupportedOperationException.class, () -> config.allowedKafkaTopics().add("newTopic"));
        assertThrows(UnsupportedOperationException.class, () -> config.allowedGrpcServices().add("newService"));
        assertNotNull(config.pipelineModuleMap());
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineModuleMap().availableModules().put("m2", null));
        assertNotNull(config.pipelineGraphConfig());
        assertThrows(UnsupportedOperationException.class, () -> config.pipelineGraphConfig().pipelines().put("p2", null));

        PipelineConfig p1Retrieved = config.pipelineGraphConfig().pipelines().get("p1");
        assertNotNull(p1Retrieved);
        assertThrows(UnsupportedOperationException.class, () -> p1Retrieved.pipelineSteps().put("s2", null));
    }

    @Test
    void testEqualityAndHashCode_WithNewStepModel() throws Exception {
        PipelineStepConfig.ProcessorInfo procInfoKafkaMod = new PipelineStepConfig.ProcessorInfo("m1", null); // Assuming m1 is a gRPC service for a Kafka-type processor
        PipelineStepConfig.ProcessorInfo procInfoGrpcMod = new PipelineStepConfig.ProcessorInfo("m-grpc", null); // Assuming m-grpc is a gRPC service
        PipelineStepConfig.ProcessorInfo procInfoInternalMod = new PipelineStepConfig.ProcessorInfo(null, "m_other_bean");

        Map<String, PipelineStepConfig.OutputTarget> outputs1 = Map.of("default_s2",
                new PipelineStepConfig.OutputTarget("s2", TransportType.GRPC, new GrpcTransportConfig("svc-grpc-target", Collections.emptyMap()), null)
        );
        PipelineStepConfig stepA1 = new PipelineStepConfig("s1", StepType.PIPELINE, "desc", "schema", createJsonConfigOptions("{}"), outputs1, 0, 1L, 1L, 1.0, 0L, procInfoKafkaMod);

        Map<String, PipelineStepConfig.OutputTarget> outputs2 = Collections.emptyMap(); // SINK
        PipelineStepConfig stepA2 = new PipelineStepConfig("s2", StepType.SINK, "desc", "schema_grpc", createJsonConfigOptions("{}"), outputs2, 0, 1L, 1L, 1.0, 0L, procInfoGrpcMod);


        PipelineStepConfig stepB1 = new PipelineStepConfig("s1", StepType.PIPELINE, "desc", "schema", createJsonConfigOptions("{}"), outputs1, 0, 1L, 1L, 1.0, 0L, procInfoKafkaMod);
        PipelineStepConfig stepB2 = new PipelineStepConfig("s2", StepType.SINK, "desc", "schema_grpc", createJsonConfigOptions("{}"), outputs2, 0, 1L, 1L, 1.0, 0L, procInfoGrpcMod);

        PipelineGraphConfig graph1 = new PipelineGraphConfig(
                Map.of("p1", new PipelineConfig("p1", Map.of(stepA1.stepName(), stepA1, stepA2.stepName(), stepA2)))
        );
        PipelineGraphConfig graph2 = new PipelineGraphConfig( // Identical to graph1
                Map.of("p1", new PipelineConfig("p1", Map.of(stepB1.stepName(), stepB1, stepB2.stepName(), stepB2)))
        );

        PipelineStepConfig stepC1 = new PipelineStepConfig("s_other", StepType.SINK, "desc", "schema_other", null, Collections.emptyMap(), 0, 1L, 1L, 1.0, 0L, procInfoInternalMod);
        PipelineGraphConfig graph3 = new PipelineGraphConfig( // Different graph
                Map.of("p_other", new PipelineConfig("p_other", Map.of(stepC1.stepName(), stepC1)))
        );

        PipelineModuleMap modules1 = new PipelineModuleMap(
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc", 1)))
        );
        PipelineModuleMap modules2 = new PipelineModuleMap( // Identical to modules1
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc", 1)))
        );

        PipelineClusterConfig config1 = new PipelineClusterConfig("clusterA", graph1, modules1, "p1", Set.of("t1"), Set.of("g1"));
        PipelineClusterConfig config2 = new PipelineClusterConfig("clusterA", graph2, modules2, "p1", Set.of("t1"), Set.of("g1"));
        PipelineClusterConfig config3_diff_graph = new PipelineClusterConfig("clusterA", graph3, modules1, "p1", Set.of("t1"), Set.of("g1"));

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3_diff_graph);
    }
}
