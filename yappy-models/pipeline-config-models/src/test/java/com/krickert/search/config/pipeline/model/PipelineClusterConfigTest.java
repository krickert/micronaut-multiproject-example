package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PipelineClusterConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule());
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // Helper to create JsonConfigOptions from a JSON string
    private PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(String jsonString) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (jsonString == null || jsonString.isBlank()) {
            return new PipelineStepConfig.JsonConfigOptions(null, Collections.emptyMap());
        }
        // Assuming JsonConfigOptions has a constructor that takes JsonNode and Map
        return new PipelineStepConfig.JsonConfigOptions(objectMapper.readTree(jsonString), Collections.emptyMap());
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
            for(String errorTarget : errorStepTargetNames) {
                String outputKey = "onError_" + errorTarget.replaceAll("[^a-zA-Z0-9.-]", "_");
                outputs.put(outputKey,
                        new PipelineStepConfig.OutputTarget(errorTarget, TransportType.INTERNAL, null, null) // Default error paths to INTERNAL
                );
            }
        }

        return new PipelineStepConfig(
                stepName,
                stepType == null ? com.krickert.search.config.pipeline.model.StepType.PIPELINE : stepType,
                "Description for " + stepName,
                "schema-for-" + moduleImplementationId, // customConfigSchemaId
                configOptions,
                outputs,
                0, 1000L, 30000L, 2.0, null, // Default retry/timeout
                processorInfo
        );
    }


    @Test
    void testSerializationDeserialization() throws Exception {
        Map<String, PipelineConfig> pipelinesMap = new HashMap<>();
        Map<String, PipelineStepConfig> steps1 = new HashMap<>();

        PipelineStepConfig step1_1 = createSampleStep("p1s1", "module-k1", TransportType.GRPC,
                "{\"configFor\":\"p1s1\"}", List.of("p1s2"), List.of("p1err1"),
                TransportType.GRPC, new GrpcTransportConfig("service-g1-target", Map.of("timeout", "5s")),
                com.krickert.search.config.pipeline.model.StepType.PIPELINE);

        PipelineStepConfig.ProcessorInfo step1_2ProcInfo = new PipelineStepConfig.ProcessorInfo("module-g1", null);
        PipelineStepConfig step1_2 = new PipelineStepConfig("p1s2", com.krickert.search.config.pipeline.model.StepType.SINK,
                "Description for p1s2", "schema-for-module-g1",
                createJsonConfigOptions("{\"configFor\":\"p1s2\"}"),
                Collections.emptyMap(), 0,1000L,30000L,2.0,null, step1_2ProcInfo);

        steps1.put(step1_1.stepName(), step1_1);
        steps1.put(step1_2.stepName(), step1_2);
        PipelineConfig pipeline1 = new PipelineConfig("pipeline-alpha", steps1);
        pipelinesMap.put(pipeline1.name(), pipeline1);
        PipelineGraphConfig pipelineGraphConfig = new PipelineGraphConfig(pipelinesMap);

        Map<String, PipelineModuleConfiguration> modules = new HashMap<>();
        SchemaReference schemaRefK1 = new SchemaReference("schema-for-module-k1", 1);
        PipelineModuleConfiguration moduleK1 = new PipelineModuleConfiguration("Kafka Module K1", "module-k1", schemaRefK1);
        modules.put(moduleK1.implementationId(), moduleK1);
        SchemaReference schemaRefG1 = new SchemaReference("schema-for-module-g1", 2);
        PipelineModuleConfiguration moduleG1 = new PipelineModuleConfiguration("gRPC Module G1", "module-g1", schemaRefG1);
        modules.put(moduleG1.implementationId(), moduleG1);
        PipelineModuleMap pipelineModuleMap = new PipelineModuleMap(modules);

        Set<String> allowedKafkaTopics = new HashSet<>(); // No Kafka outputs in this specific primary path
        Set<String> allowedGrpcServices = new HashSet<>(Arrays.asList("service-g1-target", "module-k1", "module-g1"));

        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster-001", pipelineGraphConfig, pipelineModuleMap,
                null, allowedKafkaTopics, allowedGrpcServices
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineClusterConfig JSON (New Model):\n" + json);
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        assertEquals("test-cluster-001", deserialized.clusterName());
        assertNotNull(deserialized.pipelineGraphConfig());
        assertEquals(1, deserialized.pipelineGraphConfig().pipelines().size());
        PipelineConfig deserializedPipeline = deserialized.pipelineGraphConfig().pipelines().get("pipeline-alpha");
        assertNotNull(deserializedPipeline);
        assertEquals(2, deserializedPipeline.pipelineSteps().size());

        PipelineStepConfig deserializedP1S1 = deserializedPipeline.pipelineSteps().get("p1s1");
        assertNotNull(deserializedP1S1);
        assertEquals("module-k1", deserializedP1S1.processorInfo().grpcServiceName());

        assertNotNull(deserializedP1S1.outputs().get("default_p1s2"));
        assertEquals("p1s2", deserializedP1S1.outputs().get("default_p1s2").targetStepName());
        assertEquals(TransportType.GRPC, deserializedP1S1.outputs().get("default_p1s2").transportType());
        assertEquals("service-g1-target", deserializedP1S1.outputs().get("default_p1s2").grpcTransport().serviceName());

        assertNotNull(deserializedP1S1.outputs().get("onError_p1err1"));
        assertEquals("p1err1", deserializedP1S1.outputs().get("onError_p1err1").targetStepName());
        assertEquals(TransportType.INTERNAL, deserializedP1S1.outputs().get("onError_p1err1").transportType());

        PipelineStepConfig deserializedP1S2 = deserializedPipeline.pipelineSteps().get("p1s2");
        assertNotNull(deserializedP1S2);
        assertEquals(StepType.SINK, deserializedP1S2.stepType());
        assertEquals("module-g1", deserializedP1S2.processorInfo().grpcServiceName());
        assertTrue(deserializedP1S2.outputs().isEmpty());

        assertNotNull(deserialized.pipelineModuleMap());
        assertEquals(2, deserialized.pipelineModuleMap().availableModules().size());
        assertTrue(deserialized.pipelineModuleMap().availableModules().containsKey("module-k1"));
        assertEquals("schema-for-module-g1", deserialized.pipelineModuleMap().availableModules().get("module-g1").customConfigSchemaReference().subject());

        assertEquals(allowedKafkaTopics, deserialized.allowedKafkaTopics());
        assertEquals(allowedGrpcServices, deserialized.allowedGrpcServices());
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
        String jsonToLoad = """
        {
          "clusterName": "loaded-cluster-from-file",
          "defaultPipelineName": "dataPipelineFromFile",
          "pipelineGraphConfig": {
            "pipelines": {
              "dataPipelineFromFile": {
                "name": "dataPipelineFromFile",
                "pipelineSteps": {
                  "ingestStepFile": {
                    "stepName": "ingestStepFile",
                    "stepType": "INITIAL_PIPELINE",
                    "description": "Ingests data from a conceptual file source",
                    "customConfigSchemaId": "fileIngestSchemaV1",
                    "customConfig": { "jsonConfig": {"sourcePath":"/input"} },
                    "processorInfo": { "internalProcessorBeanName": "fileIngestModule" },
                    "outputs": {
                      "default": {
                        "targetStepName": "processStepFile",
                        "transportType": "KAFKA",
                        "kafkaTransport": { "topic": "file.ingest.to.process", "kafkaProducerProperties": {"retention.ms":"3600000"}}
                      }
                    }
                  },
                  "processStepFile": {
                    "stepName": "processStepFile",
                    "stepType": "PIPELINE",
                    "description": "Processes data",
                    "processorInfo": { "grpcServiceName": "fileProcessorService" },
                    "outputs": { "default" : { "targetStepName": "archiveStepFile", "transportType": "INTERNAL"}}
                  },
                  "archiveStepFile": {
                    "stepName": "archiveStepFile",
                    "stepType": "SINK",
                    "description": "Archives data",
                    "processorInfo": { "internalProcessorBeanName": "archiverModule" },
                    "outputs": {}
                  }
                }
              }
            }
          },
          "pipelineModuleMap": {
            "availableModules": {
              "fileIngestModule": {"implementationName":"File Ingestor", "implementationId":"fileIngestModule", "customConfigSchemaReference":{"subject":"fileIngestSchema","version":1}},
              "fileProcessorService": {"implementationName":"File Processor", "implementationId":"fileProcessorService", "customConfigSchemaReference":{"subject":"fileProcSchema","version":1}},
              "archiverModule": {"implementationName":"Archiver", "implementationId":"archiverModule"}
            }
          },
          "allowedKafkaTopics": ["file.ingest.to.process", "some.other.topic"],
          "allowedGrpcServices": ["fileProcessorService", "another.utility.service"]
        }
        """;
        PipelineClusterConfig config = objectMapper.readValue(new ByteArrayInputStream(jsonToLoad.getBytes(StandardCharsets.UTF_8)), PipelineClusterConfig.class);

        assertEquals("loaded-cluster-from-file", config.clusterName());
        assertEquals("dataPipelineFromFile", config.defaultPipelineName());

        assertNotNull(config.pipelineGraphConfig());
        assertEquals(1, config.pipelineGraphConfig().pipelines().size());
        PipelineConfig p1 = config.pipelineGraphConfig().pipelines().get("dataPipelineFromFile");
        assertNotNull(p1);
        assertEquals("dataPipelineFromFile", p1.name());
        assertEquals(3, p1.pipelineSteps().size());

        PipelineStepConfig ingestStep = p1.pipelineSteps().get("ingestStepFile");
        assertNotNull(ingestStep);
        assertEquals("ingestStepFile", ingestStep.stepName());
        assertEquals(StepType.INITIAL_PIPELINE, ingestStep.stepType());
        assertEquals("fileIngestModule", ingestStep.processorInfo().internalProcessorBeanName());
        assertEquals("file.ingest.to.process", ingestStep.outputs().get("default").kafkaTransport().topic());

        PipelineStepConfig processStep = p1.pipelineSteps().get("processStepFile");
        assertNotNull(processStep);
        assertEquals("fileProcessorService", processStep.processorInfo().grpcServiceName());

        assertNotNull(config.pipelineModuleMap());
        assertEquals(3, config.pipelineModuleMap().availableModules().size());
        assertTrue(config.pipelineModuleMap().availableModules().containsKey("fileProcessorService"));
        assertEquals("fileProcSchema", config.pipelineModuleMap().availableModules().get("fileProcessorService").customConfigSchemaReference().subject());

        assertTrue(config.allowedKafkaTopics().contains("file.ingest.to.process"));
        assertTrue(config.allowedGrpcServices().contains("fileProcessorService"));
    }

    @Test
    void testImmutabilityOfCollections() throws Exception {
        PipelineStepConfig.ProcessorInfo pi = new PipelineStepConfig.ProcessorInfo(null, "m1-bean");
        PipelineStepConfig step = new PipelineStepConfig(
                "s1", StepType.PIPELINE, null,null, null, Collections.emptyMap(),
                0,1L,1L,1.0,1L, pi);

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
        PipelineStepConfig stepA1 = new PipelineStepConfig("s1", StepType.PIPELINE, "desc", "schema", createJsonConfigOptions("{}"), outputs1, 0,1L,1L,1.0,0L, procInfoKafkaMod);

        Map<String, PipelineStepConfig.OutputTarget> outputs2 = Collections.emptyMap(); // SINK
        PipelineStepConfig stepA2 = new PipelineStepConfig("s2", StepType.SINK, "desc", "schema_grpc", createJsonConfigOptions("{}"), outputs2, 0,1L,1L,1.0,0L, procInfoGrpcMod);


        PipelineStepConfig stepB1 = new PipelineStepConfig("s1", StepType.PIPELINE, "desc", "schema", createJsonConfigOptions("{}"), outputs1, 0,1L,1L,1.0,0L, procInfoKafkaMod);
        PipelineStepConfig stepB2 = new PipelineStepConfig("s2", StepType.SINK, "desc", "schema_grpc", createJsonConfigOptions("{}"), outputs2, 0,1L,1L,1.0,0L, procInfoGrpcMod);

        PipelineGraphConfig graph1 = new PipelineGraphConfig(
                Map.of("p1", new PipelineConfig("p1", Map.of(stepA1.stepName(), stepA1, stepA2.stepName(), stepA2)))
        );
        PipelineGraphConfig graph2 = new PipelineGraphConfig( // Identical to graph1
                Map.of("p1", new PipelineConfig("p1", Map.of(stepB1.stepName(), stepB1, stepB2.stepName(), stepB2)))
        );

        PipelineStepConfig stepC1 = new PipelineStepConfig("s_other", StepType.SINK, "desc", "schema_other",null, Collections.emptyMap(),0,1L,1L,1.0,0L, procInfoInternalMod);
        PipelineGraphConfig graph3 = new PipelineGraphConfig( // Different graph
                Map.of("p_other", new PipelineConfig("p_other", Map.of(stepC1.stepName(), stepC1)))
        );

        PipelineModuleMap modules1 = new PipelineModuleMap(
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc",1)))
        );
        PipelineModuleMap modules2 = new PipelineModuleMap( // Identical to modules1
                Map.of("m1", new PipelineModuleConfiguration("Mod1", "m1", new SchemaReference("schema", 1)),
                        "m-grpc", new PipelineModuleConfiguration("ModGrpc", "m-grpc", new SchemaReference("schema-grpc",1)))
        );

        PipelineClusterConfig config1 = new PipelineClusterConfig("clusterA", graph1, modules1, "p1", Set.of("t1"), Set.of("g1"));
        PipelineClusterConfig config2 = new PipelineClusterConfig("clusterA", graph2, modules2, "p1", Set.of("t1"), Set.of("g1"));
        PipelineClusterConfig config3_diff_graph = new PipelineClusterConfig("clusterA", graph3, modules1, "p1", Set.of("t1"), Set.of("g1"));

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotEquals(config1, config3_diff_graph);
    }
}