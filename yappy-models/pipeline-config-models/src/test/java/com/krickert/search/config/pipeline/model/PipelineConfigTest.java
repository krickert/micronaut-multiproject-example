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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PipelineConfigTest {

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
        // Assuming JsonConfigOptions record has a constructor: JsonConfigOptions(JsonNode jsonConfig, Map<String, String> configParams)
        // And your record definition handles null JsonNode if jsonString is null/blank
        // This helper uses the constructor that takes a JsonNode, which is fine.
        return new PipelineStepConfig.JsonConfigOptions(objectMapper.readTree(jsonString));
    }

    // --- Refactored Helper Methods for creating new PipelineStepConfig records ---
    private PipelineStepConfig createSampleStep(
            String stepName,
            String moduleImplementationId, // Used for ProcessorInfo
            TransportType processorNatureOld, // KAFKA/GRPC implies grpcServiceName, INTERNAL implies internalProcessorBeanName
            PipelineStepConfig.JsonConfigOptions customConfig, // Changed to pass JsonConfigOptions directly
            List<String> nextStepTargetNames,    // For the "default" outputs
            List<String> errorStepTargetNames,   // For the "onError" outputs
            TransportType defaultOutputTransportType, // Transport for the "default" output
            Object defaultOutputTransportConfig, // KafkaTransportConfig or GrpcTransportConfig for "default" output
            StepType newStepType // The new StepType enum (PIPELINE, SINK, INITIAL_PIPELINE)
    ) { // Removed throws com.fasterxml.jackson.core.JsonProcessingException as customConfig is pre-parsed

        PipelineStepConfig.ProcessorInfo processorInfo;
        if (processorNatureOld == TransportType.KAFKA || processorNatureOld == TransportType.GRPC) {
            processorInfo = new PipelineStepConfig.ProcessorInfo(moduleImplementationId, null);
        } else { // INTERNAL
            processorInfo = new PipelineStepConfig.ProcessorInfo(null, moduleImplementationId);
        }

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
                } else if (defaultOutputTransportConfig != null) {
                    throw new IllegalArgumentException("Mismatched output transport config for type: " + defaultOutputTransportType + " for target " + nextTarget);
                }
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
                        new PipelineStepConfig.OutputTarget(errorTarget, TransportType.INTERNAL, null, null)
                );
            }
        }

        return new PipelineStepConfig(
                stepName,
                newStepType == null ? com.krickert.search.config.pipeline.model.StepType.PIPELINE : newStepType,
                "Description for " + stepName,
                "schema-for-" + moduleImplementationId,
                customConfig, // Pass JsonConfigOptions directly
                outputs,
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );
    }


    @Test
    void testSerializationDeserialization_WithKafkaAndGrpcSteps() throws Exception {
        Map<String, PipelineStepConfig> steps = new HashMap<>();

        PipelineStepConfig.JsonConfigOptions customConfigKafka = createJsonConfigOptions("{\"source\":\"kafka\"}");
        PipelineStepConfig kafkaStep = createSampleStep(
                "kafka-processor-step", "kafka-processor-module", TransportType.GRPC,
                customConfigKafka, // Pass pre-created JsonConfigOptions
                List.of("grpc-validator-step"), List.of("kafka-error-logger"),
                TransportType.GRPC,
                new GrpcTransportConfig("grpc-validation-service-target", Map.of("clientProp", "val1")),
                com.krickert.search.config.pipeline.model.StepType.PIPELINE);

        Map<String, PipelineStepConfig.OutputTarget> kafkaStepOutputsModified = new HashMap<>(kafkaStep.outputs());
        kafkaStepOutputsModified.put("onError_" + "kafka-error-logger".replaceAll("[^a-zA-Z0-9.-]", "_"), // Match key generation
                new PipelineStepConfig.OutputTarget(
                        "kafka-error-logger", TransportType.KAFKA, null,
                        new KafkaTransportConfig("error.topic.for.kafka.step", Map.of("errorProdProp", "valE"))
                ));
        kafkaStep = new PipelineStepConfig(kafkaStep.stepName(), kafkaStep.stepType(), kafkaStep.description(),
                kafkaStep.customConfigSchemaId(), kafkaStep.customConfig(),
                kafkaStepOutputsModified, kafkaStep.maxRetries(), kafkaStep.retryBackoffMs(),
                kafkaStep.maxRetryBackoffMs(), kafkaStep.retryBackoffMultiplier(),
                kafkaStep.stepTimeoutMs(), kafkaStep.processorInfo());
        steps.put(kafkaStep.stepName(), kafkaStep);

        PipelineStepConfig.JsonConfigOptions customConfigGrpc = createJsonConfigOptions("{\"validatorType\":\"strict\"}");
        PipelineStepConfig grpcStep = createSampleStep(
                "grpc-validator-step", "grpc-validator-module", TransportType.INTERNAL,
                customConfigGrpc, // Pass pre-created JsonConfigOptions
                List.of("final-internal-step"), null,
                TransportType.INTERNAL, null,
                com.krickert.search.config.pipeline.model.StepType.PIPELINE);
        steps.put(grpcStep.stepName(), grpcStep);

        PipelineStepConfig.ProcessorInfo internalSinkProcessor = new PipelineStepConfig.ProcessorInfo(null, "internal-aggregator-module");
        PipelineStepConfig internalStep = new PipelineStepConfig(
                "final-internal-step", StepType.SINK, "Final internal aggregation", null,
                null, Collections.emptyMap(),
                0, 1000L, 30000L, 2.0, null, internalSinkProcessor
        );
        steps.put(internalStep.stepName(), internalStep);

        PipelineConfig config = new PipelineConfig("test-complex-pipeline", steps);
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineConfig JSON (New Model):\n" + json);

        PipelineConfig deserialized = objectMapper.readValue(json, PipelineConfig.class);

        assertEquals("test-complex-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertEquals(3, deserialized.pipelineSteps().size());

        PipelineStepConfig deserializedKafkaStep = deserialized.pipelineSteps().get("kafka-processor-step");
        assertNotNull(deserializedKafkaStep);
        assertEquals("kafka-processor-step", deserializedKafkaStep.stepName());
        assertNotNull(deserializedKafkaStep.processorInfo());
        assertEquals("kafka-processor-module", deserializedKafkaStep.processorInfo().grpcServiceName());
        assertNotNull(deserializedKafkaStep.customConfig());
        // Corrected Assertion: Use toString() for JsonNode object comparison as JSON string
        assertEquals("{\"source\":\"kafka\"}", deserializedKafkaStep.customConfig().jsonConfig().toString());

        assertNotNull(deserializedKafkaStep.outputs().get("default_grpc-validator-step")); // Adjusted key
        PipelineStepConfig.OutputTarget kafkaDefaultOut = deserializedKafkaStep.outputs().get("default_grpc-validator-step");
        assertEquals("grpc-validator-step", kafkaDefaultOut.targetStepName());
        assertEquals(TransportType.GRPC, kafkaDefaultOut.transportType());
        assertEquals("grpc-validation-service-target", kafkaDefaultOut.grpcTransport().serviceName());

        assertNotNull(deserializedKafkaStep.outputs().get("onError_kafka-error-logger")); // Adjusted key
        PipelineStepConfig.OutputTarget kafkaErrorOut = deserializedKafkaStep.outputs().get("onError_kafka-error-logger");
        assertEquals("kafka-error-logger", kafkaErrorOut.targetStepName());
        assertEquals(TransportType.KAFKA, kafkaErrorOut.transportType());
        assertEquals("error.topic.for.kafka.step", kafkaErrorOut.kafkaTransport().topic());

        PipelineStepConfig deserializedGrpcStep = deserialized.pipelineSteps().get("grpc-validator-step");
        assertNotNull(deserializedGrpcStep);
        assertEquals("grpc-validator-step", deserializedGrpcStep.stepName());
        assertNotNull(deserializedGrpcStep.processorInfo());
        assertEquals("grpc-validator-module", deserializedGrpcStep.processorInfo().internalProcessorBeanName());

        assertNotNull(deserializedGrpcStep.outputs().get("default_final-internal-step")); // Adjusted key
        PipelineStepConfig.OutputTarget grpcDefaultOut = deserializedGrpcStep.outputs().get("default_final-internal-step");
        assertEquals("final-internal-step", grpcDefaultOut.targetStepName());
        assertEquals(TransportType.INTERNAL, grpcDefaultOut.transportType());
        assertNull(grpcDefaultOut.kafkaTransport());
        assertNull(grpcDefaultOut.grpcTransport());

        PipelineStepConfig deserializedInternalStep = deserialized.pipelineSteps().get("final-internal-step");
        assertNotNull(deserializedInternalStep);
        assertEquals("final-internal-step", deserializedInternalStep.stepName());
        assertEquals(StepType.SINK, deserializedInternalStep.stepType());
        assertNotNull(deserializedInternalStep.processorInfo());
        assertEquals("internal-aggregator-module", deserializedInternalStep.processorInfo().internalProcessorBeanName());
        assertTrue(deserializedInternalStep.outputs().isEmpty());
    }

    @Test
    void testValidation_PipelineConfigConstructor() {
        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new PipelineConfig(null, Collections.emptyMap()));
        assertTrue(e1.getMessage().contains("name cannot be null"));

        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new PipelineConfig("", Collections.emptyMap()));
        assertTrue(e2.getMessage().contains("PipelineConfig name cannot be null or blank"));

        PipelineConfig configWithNullSteps = new PipelineConfig("test-pipeline-null-steps", null);
        assertNotNull(configWithNullSteps.pipelineSteps(), "pipelineSteps should be an empty map, not null");
        assertTrue(configWithNullSteps.pipelineSteps().isEmpty());
    }

    @Test
    void testJsonPropertyNames() throws Exception {
        Map<String, PipelineStepConfig> steps = new HashMap<>();
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(null, "module-bean");
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        outputs.put("default_next-target", new PipelineStepConfig.OutputTarget( // Adjusted key
                "next-target", TransportType.KAFKA, null,
                new KafkaTransportConfig("out-topic", Map.of("key", "val"))
        ));

        PipelineStepConfig step = new PipelineStepConfig(
                "json-prop-step", StepType.PIPELINE, "desc", "schemaX",
                createJsonConfigOptions("{\"cfg\":\"val\"}"),
                outputs, 0, 1L, 2L, 1.0, 3L, processorInfo
        );
        steps.put(step.stepName(), step);
        PipelineConfig config = new PipelineConfig("json-prop-pipeline", steps);

        String json = objectMapper.writeValueAsString(config);
        System.out.println("JSON for PipelineConfigTest.testJsonPropertyNames (New Model):\n" + json);

        assertTrue(json.contains("\"name\" : \"json-prop-pipeline\""));
        assertTrue(json.contains("\"pipelineSteps\" : {"));
        assertTrue(json.contains("\"json-prop-step\" : {"));
        assertTrue(json.contains("\"stepName\" : \"json-prop-step\""));
        assertTrue(json.contains("\"processorInfo\" : {"));
        assertTrue(json.contains("\"internalProcessorBeanName\" : \"module-bean\""));
        assertTrue(json.contains("\"outputs\" : {"));
        assertTrue(json.contains("\"default_next-target\" : {")); // Adjusted key
        assertTrue(json.contains("\"targetStepName\" : \"next-target\""));
        assertTrue(json.contains("\"transportType\" : \"KAFKA\""));
        assertTrue(json.contains("\"kafkaTransport\" : {"));
        assertTrue(json.contains("\"topic\" : \"out-topic\""));
    }

    @Test
    void testLoadFromJsonFile_WithNewModel() throws Exception {
        String jsonToLoad = """
        {
          "name": "loaded-pipeline-from-config-test",
          "pipelineSteps": {
            "entryStep": {
              "stepName": "entryStep",
              "stepType": "INITIAL_PIPELINE",
              "description": "Entry point step",
              "processorInfo": { "internalProcessorBeanName": "entry-module-bean" },
              "outputs": {
                "default_processingStep": {
                  "targetStepName": "processingStep",
                  "transportType": "KAFKA",
                  "kafkaTransport": {
                    "topic": "pipeline.entryStep.to.processingStep",
                    "kafkaProducerProperties": {"linger.ms": "10"}
                  }
                }
              }
            },
            "processingStep": {
              "stepName": "processingStep",
              "stepType": "PIPELINE",
              "description": "Processes data",
              "customConfig": {"jsonConfig" : {"mode":"fast"}, "configParams": {"timeout":"30s"}},
              "customConfigSchemaId": "procSchemaV1",
              "processorInfo": { "grpcServiceName": "processor-grpc-service" },
              "maxRetries": 2,
              "outputs": {
                  "result_sinkStep": { "targetStepName": "sinkStep", "transportType": "INTERNAL"}
              }
            },
            "sinkStep": {
                "stepName": "sinkStep",
                "stepType": "SINK",
                "description": "Final SINK step",
                "processorInfo": {"internalProcessorBeanName": "finalDataStoreBean"},
                "outputs": {}
            }
          }
        }
        """;
        PipelineConfig config = objectMapper.readValue(new ByteArrayInputStream(jsonToLoad.getBytes(StandardCharsets.UTF_8)), PipelineConfig.class);

        assertEquals("loaded-pipeline-from-config-test", config.name());
        assertNotNull(config.pipelineSteps());
        assertEquals(3, config.pipelineSteps().size());

        PipelineStepConfig entryStep = config.pipelineSteps().get("entryStep");
        assertNotNull(entryStep);
        assertEquals("entry-module-bean", entryStep.processorInfo().internalProcessorBeanName());
        assertNotNull(entryStep.outputs().get("default_processingStep"));
        assertEquals("processingStep", entryStep.outputs().get("default_processingStep").targetStepName());
        assertEquals(TransportType.KAFKA, entryStep.outputs().get("default_processingStep").transportType());
        assertEquals("pipeline.entryStep.to.processingStep", entryStep.outputs().get("default_processingStep").kafkaTransport().topic());
        assertEquals(StepType.INITIAL_PIPELINE, entryStep.stepType());

        PipelineStepConfig processingStep = config.pipelineSteps().get("processingStep");
        assertNotNull(processingStep);
        assertEquals("processor-grpc-service", processingStep.processorInfo().grpcServiceName());
        assertNotNull(processingStep.customConfig().jsonConfig());
        assertEquals("{\"mode\":\"fast\"}",processingStep.customConfig().jsonConfig().toString());
        assertEquals("30s", processingStep.customConfig().configParams().get("timeout"));
        assertEquals("procSchemaV1", processingStep.customConfigSchemaId());
        assertEquals(2, processingStep.maxRetries());
        assertEquals("sinkStep", processingStep.outputs().get("result_sinkStep").targetStepName());

        PipelineStepConfig sinkStep = config.pipelineSteps().get("sinkStep");
        assertNotNull(sinkStep);
        assertEquals(StepType.SINK, sinkStep.stepType());
        assertTrue(sinkStep.outputs().isEmpty());
        assertEquals("finalDataStoreBean", sinkStep.processorInfo().internalProcessorBeanName());
    }

    @Test
    void testImmutabilityOfPipelineStepsMapInPipelineConfig() throws Exception { // Added throws for createJsonConfigOptions
        Map<String, PipelineStepConfig> initialSteps = new HashMap<>();
        PipelineStepConfig.ProcessorInfo pi = new PipelineStepConfig.ProcessorInfo(null, "m1-bean");
        // Using the record constructor directly for PipelineStepConfig
        PipelineStepConfig internalStep = new PipelineStepConfig(
                "s1", StepType.PIPELINE, "desc", "schema",
                createJsonConfigOptions(null), // customConfig
                Collections.emptyMap(), // outputs
                0,1L,1L,1.0,1L, // retry & timeout
                pi);
        initialSteps.put(internalStep.stepName(), internalStep);

        PipelineConfig config = new PipelineConfig("immutable-test-pipeline", initialSteps);
        Map<String, PipelineStepConfig> retrievedSteps = config.pipelineSteps();

        assertThrows(UnsupportedOperationException.class, () -> retrievedSteps.put("s2", null),
                "PipelineConfig.pipelineSteps map should be unmodifiable after construction.");

        PipelineStepConfig.ProcessorInfo pi2 = new PipelineStepConfig.ProcessorInfo(null, "m2-bean");
        PipelineStepConfig anotherStep = new PipelineStepConfig(
                "s2", StepType.PIPELINE, "desc2", "schema2",
                createJsonConfigOptions(null), Collections.emptyMap(),
                0,1L,1L,1.0,1L,
                pi2);
        initialSteps.put("s2", anotherStep);
        assertEquals(1, config.pipelineSteps().size(), "Modifying the original map should not affect the config's map.");
        assertFalse(config.pipelineSteps().containsKey("s2"));
    }
}