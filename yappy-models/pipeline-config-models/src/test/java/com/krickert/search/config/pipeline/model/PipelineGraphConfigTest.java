package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineGraphConfigTest {

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
            // Assuming JsonConfigOptions record has a constructor: JsonConfigOptions(JsonNode jsonConfig, Map<String, String> configParams)
            return new PipelineStepConfig.JsonConfigOptions(null, Collections.emptyMap());
        }
        // Assuming JsonConfigOptions has a constructor taking JsonNode for the first param
        return new PipelineStepConfig.JsonConfigOptions(objectMapper.readTree(jsonString));
    }

    @Test
    void testSerializationDeserialization_KafkaStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig.JsonConfigOptions customConfig = createJsonConfigOptions("{\"key\":\"kafkaValue\"}");

        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                "kafka-module",
                null
        );

        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        KafkaTransportConfig kafkaOutputToNext = new KafkaTransportConfig(
                "pipelineId.stepId.output",
                Map.of("acks", "all")
        );
        outputs.put("default", // Using simple key for clarity in this direct test
                new PipelineStepConfig.OutputTarget(
                        "next-logical-step",
                        TransportType.KAFKA,
                        null,
                        kafkaOutputToNext
                )
        );
        KafkaTransportConfig kafkaOutputToError = new KafkaTransportConfig(
                "pipeline.error-logical-step.input",
                Collections.emptyMap()
        );
        outputs.put("onError", // Using simple key
                new PipelineStepConfig.OutputTarget(
                        "error-logical-step",
                        TransportType.KAFKA,
                        null,
                        kafkaOutputToError
                )
        );

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("input-topic-for-kafka-test-step"),
                        "consumer-group-for-kafka-test-step",
                        Map.of("auto.offset.reset", "earliest")
                )
        );

        PipelineStepConfig kafkaStep = new PipelineStepConfig(
                "kafka-test-step",
                com.krickert.search.config.pipeline.model.StepType.PIPELINE,
                "A Kafka test step",
                null,
                customConfig,
                kafkaInputs,
                outputs,
                0,
                1000L,
                30000L,
                2.0,
                null,
                processorInfo
        );
        steps.put(kafkaStep.stepName(), kafkaStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-kafka", steps);
        pipelines.put(pipeline.name(), pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized Kafka Step JSON (New Record Model):\n" + json);

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        assertNotNull(deserialized.pipelines());
        assertEquals(1, deserialized.pipelines().size());

        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-kafka");
        assertNotNull(deserializedPipeline);
        assertEquals("test-pipeline-kafka", deserializedPipeline.name());

        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("kafka-test-step");
        assertNotNull(deserializedStep);
        assertEquals("kafka-test-step", deserializedStep.stepName());

        assertNotNull(deserializedStep.processorInfo());
        assertEquals("kafka-module", deserializedStep.processorInfo().grpcServiceName());
        assertNull(deserializedStep.processorInfo().internalProcessorBeanName());

        assertNotNull(deserializedStep.customConfig());
        // CORRECTED ASSERTION HERE:
        assertEquals("{\"key\":\"kafkaValue\"}", deserializedStep.customConfig().jsonConfig().toString());

        assertNotNull(deserializedStep.outputs());
        PipelineStepConfig.OutputTarget defaultOutput = deserializedStep.outputs().get("default");
        assertNotNull(defaultOutput);
        assertEquals("next-logical-step", defaultOutput.targetStepName());
        assertEquals(TransportType.KAFKA, defaultOutput.transportType());
        assertNotNull(defaultOutput.kafkaTransport());
        assertEquals("pipelineId.stepId.output", defaultOutput.kafkaTransport().topic());
        assertEquals(Map.of("acks", "all"), defaultOutput.kafkaTransport().kafkaProducerProperties());

        PipelineStepConfig.OutputTarget errorOutput = deserializedStep.outputs().get("onError");
        assertNotNull(errorOutput);
        assertEquals("error-logical-step", errorOutput.targetStepName());
        assertEquals(TransportType.KAFKA, errorOutput.transportType());
        assertNotNull(errorOutput.kafkaTransport());
        assertEquals("pipeline.error-logical-step.input", errorOutput.kafkaTransport().topic());
    }

    @Test
    void testSerializationDeserialization_GrpcStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig.JsonConfigOptions customConfig = createJsonConfigOptions("{\"key\":\"grpcValue\"}");
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                "grpc-module",
                null
        );

        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        GrpcTransportConfig grpcOutputToNext = new GrpcTransportConfig(
                "my-grpc-service-id",
                Map.of("timeout", "5s")
        );
        outputs.put("default",
                new PipelineStepConfig.OutputTarget(
                        "another-next-step",
                        TransportType.GRPC,
                        grpcOutputToNext,
                        null
                )
        );

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("input-topic-for-grpc-test-step"),
                        "consumer-group-for-grpc-test-step",
                        Map.of("auto.offset.reset", "earliest")
                )
        );

        PipelineStepConfig grpcStep = new PipelineStepConfig(
                "grpc-test-step",
                com.krickert.search.config.pipeline.model.StepType.PIPELINE,
                "A gRPC test step",
                null,
                customConfig,
                kafkaInputs,
                outputs,
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );
        steps.put(grpcStep.stepName(), grpcStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-grpc", steps);
        pipelines.put(pipeline.name(), pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized gRPC Step JSON (New Record Model):\n" + json);

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);
        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-grpc");
        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("grpc-test-step");

        assertNotNull(deserializedStep);
        assertEquals("grpc-test-step", deserializedStep.stepName());
        assertNotNull(deserializedStep.processorInfo());
        assertEquals("grpc-module", deserializedStep.processorInfo().grpcServiceName());
        assertNotNull(deserializedStep.customConfig());
        // CORRECTED ASSERTION HERE:
        assertEquals("{\"key\":\"grpcValue\"}", deserializedStep.customConfig().jsonConfig().toString());


        assertNotNull(deserializedStep.outputs().get("default"));
        PipelineStepConfig.OutputTarget defaultOutput = deserializedStep.outputs().get("default");
        assertEquals("another-next-step", defaultOutput.targetStepName());
        assertEquals(TransportType.GRPC, defaultOutput.transportType());
        assertNotNull(defaultOutput.grpcTransport());
        assertEquals("my-grpc-service-id", defaultOutput.grpcTransport().serviceName());
        assertEquals(Map.of("timeout", "5s"), defaultOutput.grpcTransport().grpcClientProperties());
        assertEquals(1, deserializedStep.outputs().size());
    }

    @Test
    void testSerializationDeserialization_InternalStep() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null,
                "internal-module"
        );

        Map<String, PipelineStepConfig.OutputTarget> outputs = Collections.emptyMap();

        // Create an empty list of KafkaInputDefinition for internal step
        List<KafkaInputDefinition> kafkaInputs = Collections.emptyList();

        PipelineStepConfig internalStep = new PipelineStepConfig(
                "internal-test-step",
                com.krickert.search.config.pipeline.model.StepType.PIPELINE,
                "An internal test step",
                null, null,
                kafkaInputs,
                outputs,
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );
        steps.put(internalStep.stepName(), internalStep);

        PipelineConfig pipeline = new PipelineConfig("test-pipeline-internal", steps);
        pipelines.put(pipeline.name(), pipeline);

        PipelineGraphConfig config = new PipelineGraphConfig(pipelines);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized Internal Step JSON (New Record Model):\n" + json);

        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);
        PipelineConfig deserializedPipeline = deserialized.pipelines().get("test-pipeline-internal");
        PipelineStepConfig deserializedStep = deserializedPipeline.pipelineSteps().get("internal-test-step");

        assertNotNull(deserializedStep);
        assertEquals("internal-test-step", deserializedStep.stepName());
        assertNotNull(deserializedStep.processorInfo());
        assertEquals("internal-module", deserializedStep.processorInfo().internalProcessorBeanName());
        assertNull(deserializedStep.processorInfo().grpcServiceName());
        assertNull(deserializedStep.customConfig());
        assertTrue(deserializedStep.outputs().isEmpty());
    }


    @Test
    void testNullHandling_PipelineGraphConfig() throws Exception {
        PipelineGraphConfig config = new PipelineGraphConfig(null);
        String json = objectMapper.writeValueAsString(config);
        PipelineGraphConfig deserialized = objectMapper.readValue(json, PipelineGraphConfig.class);

        assertNotNull(deserialized.pipelines());
        assertTrue(deserialized.pipelines().isEmpty());
    }

    @Test
    void testJsonPropertyNames_WithNewModel() throws Exception {
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo("test-module-grpc", null);
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        outputs.put("default", new PipelineStepConfig.OutputTarget(
                "next-step-id",
                TransportType.KAFKA,
                null,
                new KafkaTransportConfig("topic-for-next-step", Map.of("prop", "val"))
        ));
        outputs.put("error", new PipelineStepConfig.OutputTarget(
                "error-step-id",
                TransportType.INTERNAL,
                null, null
        ));

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("input-topic-for-test-step"),
                        "consumer-group-for-test-step",
                        Map.of("auto.offset.reset", "earliest")
                )
        );

        PipelineStepConfig step = new PipelineStepConfig(
                "test-step",
                com.krickert.search.config.pipeline.model.StepType.PIPELINE,
                "Test Description", "schema-id-123",
                createJsonConfigOptions("{\"configKey\":\"configValue\"}"),
                kafkaInputs,
                outputs,
                1, 2000L, 60000L, 1.5, 5000L,
                processorInfo
        );
        steps.put(step.stepName(), step);
        PipelineConfig pipeline = new PipelineConfig("test-pipeline", steps);
        pipelines.put(pipeline.name(), pipeline);
        PipelineGraphConfig config = new PipelineGraphConfig(Map.of(pipeline.name(), pipeline));

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized JSON (New Record Model - Property Names Test):\n" + json);

        assertTrue(json.contains("\"pipelines\""));
        assertTrue(json.contains("\"test-pipeline\""));
        assertTrue(json.contains("\"stepName\" : \"test-step\""));
        assertTrue(json.contains("\"stepType\" : \"PIPELINE\""));
        assertTrue(json.contains("\"processorInfo\" : {"));
        assertTrue(json.contains("\"grpcServiceName\" : \"test-module-grpc\""));
        assertTrue(json.contains("\"outputs\" : {"));
        assertTrue(json.contains("\"default\" : {"));
        assertTrue(json.contains("\"targetStepName\" : \"next-step-id\""));
        assertTrue(json.contains("\"transportType\" : \"KAFKA\"")); // Inside OutputTarget
        assertTrue(json.contains("\"kafkaTransport\" : {"));        // Inside OutputTarget
        assertTrue(json.contains("\"topic\" : \"topic-for-next-step\""));
        assertTrue(json.contains("\"error\" : {"));
        assertTrue(json.contains("\"targetStepName\" : \"error-step-id\""));
        assertTrue(json.contains("\"transportType\" : \"INTERNAL\""));

        assertFalse(json.contains("\"pipelineStepId\""));
        assertFalse(json.contains("\"pipelineImplementationId\""));

        String stepJsonBlock = json.substring(json.indexOf("\"test-step\" : {"));
        // Find the end of the "test-step" object, carefully handling nested objects like "outputs"
        int braceCount = 0;
        int endIndex = -1;
        for (int i = json.indexOf("\"test-step\" : {") + "\"test-step\" : {".length(); i < json.length(); i++) {
            if (json.charAt(i) == '{') {
                braceCount++;
            } else if (json.charAt(i) == '}') {
                if (braceCount == 0) {
                    endIndex = i;
                    break;
                }
                braceCount--;
            }
        }
        if (endIndex != -1) {
            stepJsonBlock = json.substring(json.indexOf("\"test-step\" : {"), endIndex + 1);

            // These assertions check that top-level transportType/kafkaConfig specific to step *execution* are gone.
            // The transportType inside outputs.default.transportType is fine.
            assertFalse(stepJsonBlock.matches("(?s).*\"stepName\"\\s*:\\s*\"test-step\"[^\\}]*\"transportType\"\\s*:\\s*\"KAFKA\".*"), "Step itself should not have a direct top-level transportType for its execution nature");
            assertFalse(stepJsonBlock.matches("(?s).*\"stepName\"\\s*:\\s*\"test-step\"[^\\}]*\"kafkaConfig\"\\s*:\\s*\\{.*"), "Step itself should not have a direct top-level kafkaConfig for its execution");
        } else {
            fail("Could not properly extract stepJsonBlock to perform negative assertions.");
        }

        assertFalse(json.contains("\"nextSteps\" : ["));
        assertFalse(json.contains("\"errorSteps\" : ["));
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        String jsonToLoad = """
                {
                  "pipelines": {
                    "dataIngestionPipeline": {
                      "name": "dataIngestionPipeline",
                      "pipelineSteps": {
                        "receiveRawData": {
                          "stepName": "receiveRawData",
                          "stepType": "INITIAL_PIPELINE",
                          "description": "Receives raw data",
                          "customConfigSchemaId": "rawDocEventSchema_v1",
                          "customConfig": {
                            "jsonConfig": {"validationSchemaId":"rawDocEventSchema_v1"}
                          },
                          "processorInfo": {
                            "internalProcessorBeanName": "kafkaGenericIngestor"
                          },
                          "maxRetries": 1,
                          "outputs": {
                            "default": {
                              "targetStepName": "normalizeData",
                              "transportType": "INTERNAL"
                            },
                            "onError": {
                              "targetStepName": "logIngestionError",
                              "transportType": "INTERNAL"
                            }
                          }
                        },
                        "normalizeData": {
                          "stepName": "normalizeData",
                          "stepType": "PIPELINE",
                          "description": "Normalizes data",
                          "processorInfo": { "internalProcessorBeanName": "normalizer" },
                          "outputs": {
                            "default": { "targetStepName": "enrichData", "transportType": "INTERNAL" }
                          }
                        },
                        "enrichData": {
                          "stepName": "enrichData",
                          "stepType": "PIPELINE",
                          "description": "Enriches data",
                          "processorInfo": { "grpcServiceName": "geo-enrichment-grpc-service" },
                          "outputs": {
                            "default": {
                               "targetStepName": "chunkText",
                               "transportType": "GRPC",
                               "grpcTransport": {"serviceName": "chunker-service", "grpcClientProperties": {"timeout": "10s"}}
                            }
                          }
                        },
                        "logIngestionError": {
                            "stepName": "logIngestionError",
                            "stepType": "SINK",
                            "description": "Logs errors",
                            "processorInfo": {"internalProcessorBeanName": "errorLogger"},
                            "outputs": {}
                        }
                      }
                    }
                  }
                }
                """;
        PipelineGraphConfig graphConfig = objectMapper.readValue(new ByteArrayInputStream(jsonToLoad.getBytes(StandardCharsets.UTF_8)), PipelineGraphConfig.class);

        assertNotNull(graphConfig.pipelines(), "Pipelines map should not be null");
        assertEquals(1, graphConfig.pipelines().size(), "Should be 1 pipeline defined");

        PipelineConfig ingestionPipeline = graphConfig.pipelines().get("dataIngestionPipeline");
        assertNotNull(ingestionPipeline, "dataIngestionPipeline not found");
        assertEquals("dataIngestionPipeline", ingestionPipeline.name());
        assertEquals(4, ingestionPipeline.pipelineSteps().size());

        PipelineStepConfig receiveRawData = ingestionPipeline.pipelineSteps().get("receiveRawData");
        assertNotNull(receiveRawData);
        assertEquals("receiveRawData", receiveRawData.stepName());
        assertEquals(com.krickert.search.config.pipeline.model.StepType.INITIAL_PIPELINE, receiveRawData.stepType());
        assertNotNull(receiveRawData.processorInfo().internalProcessorBeanName());
        assertEquals("kafkaGenericIngestor", receiveRawData.processorInfo().internalProcessorBeanName());
        assertNotNull(receiveRawData.customConfig().jsonConfig());
        assertEquals("{\"validationSchemaId\":\"rawDocEventSchema_v1\"}", receiveRawData.customConfig().jsonConfig().toString());
        assertEquals(1, receiveRawData.maxRetries());
        assertNotNull(receiveRawData.outputs().get("default"));
        assertEquals("normalizeData", receiveRawData.outputs().get("default").targetStepName());

        PipelineStepConfig enrichData = ingestionPipeline.pipelineSteps().get("enrichData");
        assertNotNull(enrichData);
        assertNotNull(enrichData.outputs().get("default").grpcTransport());
        assertEquals("chunker-service", enrichData.outputs().get("default").grpcTransport().serviceName());
        assertEquals("10s", enrichData.outputs().get("default").grpcTransport().grpcClientProperties().get("timeout"));

        PipelineStepConfig logError = ingestionPipeline.pipelineSteps().get("logIngestionError");
        assertNotNull(logError);
        assertEquals(com.krickert.search.config.pipeline.model.StepType.SINK, logError.stepType());
        assertTrue(logError.outputs().isEmpty());
    }

    @Test
    void testOutputTarget_Validation_MissingTransportConfig() {
        Exception eKafka = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget(
                    "s1",
                    TransportType.KAFKA,
                    null,
                    null
            );
        });
        assertTrue(eKafka.getMessage().contains("OutputTarget: KafkaTransportConfig must be provided"));

        Exception eGrpc = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget(
                    "s2",
                    TransportType.GRPC,
                    null,
                    null
            );
        });
        assertTrue(eGrpc.getMessage().contains("OutputTarget: GrpcTransportConfig must be provided"));
    }

    @Test
    void testOutputTarget_Validation_MismatchedTransportConfig() {
        KafkaTransportConfig kConf = new KafkaTransportConfig("dummy-topic", Collections.emptyMap());
        GrpcTransportConfig gConf = new GrpcTransportConfig("dummy-service", Collections.emptyMap());

        Exception eKafkaMismatch = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget("s1", TransportType.KAFKA, gConf, kConf);
        });
        assertTrue(eKafkaMismatch.getMessage().contains("OutputTarget: GrpcTransportConfig should only be provided when transportType is GRPC"));

        Exception eGrpcMismatch = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget("s2", TransportType.GRPC, gConf, kConf);
        });
        assertTrue(eGrpcMismatch.getMessage().contains("OutputTarget: KafkaTransportConfig should only be provided when transportType is KAFKA"));

        Exception eInternalMismatchKafka = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget("s3", TransportType.INTERNAL, null, kConf);
        });
        assertTrue(eInternalMismatchKafka.getMessage().contains("OutputTarget: KafkaTransportConfig should only be provided when transportType is KAFKA"));

        Exception eInternalMismatchGrpc = assertThrows(IllegalArgumentException.class, () -> {
            new PipelineStepConfig.OutputTarget("s4", TransportType.INTERNAL, gConf, null);
        });
        assertTrue(eInternalMismatchGrpc.getMessage().contains("OutputTarget: GrpcTransportConfig should only be provided when transportType is GRPC"));
    }
}
