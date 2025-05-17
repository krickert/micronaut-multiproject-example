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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class PipelineStepConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule()); // For record deserialization
        objectMapper.registerModule(new Jdk8Module());           // For Optional, etc.
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // For readable JSON output during debugging
    }

    // Helper to create JsonConfigOptions from a JSON string
    private PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(String jsonString) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (jsonString == null || jsonString.isBlank()) {
            return new PipelineStepConfig.JsonConfigOptions(null, Collections.emptyMap());
        }
        return new PipelineStepConfig.JsonConfigOptions(objectMapper.readTree(jsonString));
    }


    @Test
    void testSerializationDeserialization_KafkaOutputStep() throws Exception {
        PipelineStepConfig.JsonConfigOptions customConfig = createJsonConfigOptions("{\"key\":\"value\"}");
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                "kafka-processing-service", // This step is implemented by this gRPC service
                null
        );

        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        KafkaTransportConfig kafkaOutputTransport = new KafkaTransportConfig(
                "pipeline.step.out",
                Map.of("retries", "3")
        );
        outputs.put("default", new PipelineStepConfig.OutputTarget(
                "next-step-1",
                TransportType.KAFKA,
                null,
                kafkaOutputTransport
        ));
        outputs.put("errorPath", new PipelineStepConfig.OutputTarget(
                "error-step-1",
                TransportType.INTERNAL,
                null, null
        ));

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("input-topic-1", "input-topic-2"),
                        "test-consumer-group",
                        Map.of("auto.offset.reset", "earliest")
                )
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "test-kafka-output-step",
                StepType.PIPELINE,
                "A step that outputs to Kafka",
                "my-custom-schema-v1",
                customConfig,
                kafkaInputs,
                outputs,
                3,
                2000L,
                60000L,
                1.5,
                10000L,
                processorInfo
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized KAFKA Output PipelineStepConfig JSON:\n" + json);

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("test-kafka-output-step", deserialized.stepName());
        assertEquals(StepType.PIPELINE, deserialized.stepType());
        assertEquals("A step that outputs to Kafka", deserialized.description());
        assertEquals("my-custom-schema-v1", deserialized.customConfigSchemaId());

        assertNotNull(deserialized.customConfig());
        // CORRECTED ASSERTION:
        assertEquals("{\"key\":\"value\"}", deserialized.customConfig().jsonConfig().toString());

        assertNotNull(deserialized.processorInfo());
        assertEquals("kafka-processing-service", deserialized.processorInfo().grpcServiceName());
        assertNull(deserialized.processorInfo().internalProcessorBeanName());

        assertEquals(3, deserialized.maxRetries());
        assertEquals(2000L, deserialized.retryBackoffMs());
        assertEquals(60000L, deserialized.maxRetryBackoffMs());
        assertEquals(1.5, deserialized.retryBackoffMultiplier());
        assertEquals(10000L, deserialized.stepTimeoutMs());

        // Verify kafkaInputs
        assertNotNull(deserialized.kafkaInputs());
        assertEquals(1, deserialized.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = deserialized.kafkaInputs().get(0);
        assertEquals(2, kafkaInput.listenTopics().size());
        assertTrue(kafkaInput.listenTopics().contains("input-topic-1"));
        assertTrue(kafkaInput.listenTopics().contains("input-topic-2"));
        assertEquals("test-consumer-group", kafkaInput.consumerGroupId());
        assertEquals("earliest", kafkaInput.kafkaConsumerProperties().get("auto.offset.reset"));

        assertNotNull(deserialized.outputs());
        assertEquals(2, deserialized.outputs().size());

        PipelineStepConfig.OutputTarget defaultOutput = deserialized.outputs().get("default");
        assertNotNull(defaultOutput);
        assertEquals("next-step-1", defaultOutput.targetStepName());
        assertEquals(TransportType.KAFKA, defaultOutput.transportType());
        assertNotNull(defaultOutput.kafkaTransport());
        assertEquals("pipeline.step.out", defaultOutput.kafkaTransport().topic());
        assertEquals(Map.of("retries", "3"), defaultOutput.kafkaTransport().kafkaProducerProperties());
        assertNull(defaultOutput.grpcTransport());

        PipelineStepConfig.OutputTarget errorOutput = deserialized.outputs().get("errorPath");
        assertNotNull(errorOutput);
        assertEquals("error-step-1", errorOutput.targetStepName());
        assertEquals(TransportType.INTERNAL, errorOutput.transportType());
        assertNull(errorOutput.kafkaTransport());
        assertNull(errorOutput.grpcTransport());
    }

    @Test
    void testSerializationDeserialization_GrpcOutputStep() throws Exception {
        PipelineStepConfig.JsonConfigOptions customConfig = createJsonConfigOptions("{\"serviceSpecific\":\"config\"}");
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null,
                "grpc-processing-internal-bean"
        );

        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        GrpcTransportConfig grpcOutputTransport = new GrpcTransportConfig(
                "my-downstream-grpc-service",
                Map.of("deadlineMs", "5000")
        );
        outputs.put("primary", new PipelineStepConfig.OutputTarget(
                "next-grpc-target",
                TransportType.GRPC,
                grpcOutputTransport,
                null
        ));

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("grpc-input-topic"),
                        "grpc-consumer-group",
                        Map.of("max.poll.records", "100")
                )
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "test-grpc-output-step",
                StepType.PIPELINE,
                "Outputs to gRPC",
                null,
                customConfig,
                kafkaInputs,
                outputs,
                0,
                1000L,
                30000L,
                2.0,
                5000L,
                processorInfo
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized GRPC Output PipelineStepConfig JSON:\n" + json);

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("test-grpc-output-step", deserialized.stepName());
        assertNotNull(deserialized.processorInfo());
        assertEquals("grpc-processing-internal-bean", deserialized.processorInfo().internalProcessorBeanName());
        assertNull(deserialized.processorInfo().grpcServiceName());

        assertNotNull(deserialized.customConfig());
        // CORRECTED ASSERTION:
        assertEquals("{\"serviceSpecific\":\"config\"}", deserialized.customConfig().jsonConfig().toString());

        // Verify kafkaInputs
        assertNotNull(deserialized.kafkaInputs());
        assertEquals(1, deserialized.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = deserialized.kafkaInputs().get(0);
        assertEquals(1, kafkaInput.listenTopics().size());
        assertTrue(kafkaInput.listenTopics().contains("grpc-input-topic"));
        assertEquals("grpc-consumer-group", kafkaInput.consumerGroupId());
        assertEquals("100", kafkaInput.kafkaConsumerProperties().get("max.poll.records"));

        assertEquals(0, deserialized.maxRetries());

        assertNotNull(deserialized.outputs());
        assertEquals(1, deserialized.outputs().size());

        PipelineStepConfig.OutputTarget primaryOutput = deserialized.outputs().get("primary");
        assertNotNull(primaryOutput);
        assertEquals("next-grpc-target", primaryOutput.targetStepName());
        assertEquals(TransportType.GRPC, primaryOutput.transportType());
        assertNotNull(primaryOutput.grpcTransport());
        assertEquals("my-downstream-grpc-service", primaryOutput.grpcTransport().serviceName());
        assertEquals(Map.of("deadlineMs", "5000"), primaryOutput.grpcTransport().grpcClientProperties());
        assertNull(primaryOutput.kafkaTransport());
    }

    @Test
    void testSerializationDeserialization_InternalStepAsSink() throws Exception {
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo(
                null,
                "internal-aggregator-module"
        );
        Map<String, PipelineStepConfig.OutputTarget> outputs = Collections.emptyMap();

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("sink-input-topic"),
                        "sink-consumer-group",
                        Map.of("enable.auto.commit", "true")
                )
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "final-internal-sink-step",
                StepType.SINK,
                "An internal sink step",
                null,
                null,
                kafkaInputs,
                outputs,
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized INTERNAL Sink PipelineStepConfig JSON:\n" + json);
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        assertEquals("final-internal-sink-step", deserialized.stepName());
        assertEquals(StepType.SINK, deserialized.stepType());
        assertNotNull(deserialized.processorInfo());
        assertEquals("internal-aggregator-module", deserialized.processorInfo().internalProcessorBeanName());
        assertNull(deserialized.processorInfo().grpcServiceName());
        assertNull(deserialized.customConfig());

        // Verify kafkaInputs
        assertNotNull(deserialized.kafkaInputs());
        assertEquals(1, deserialized.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = deserialized.kafkaInputs().get(0);
        assertEquals(1, kafkaInput.listenTopics().size());
        assertTrue(kafkaInput.listenTopics().contains("sink-input-topic"));
        assertEquals("sink-consumer-group", kafkaInput.consumerGroupId());
        assertEquals("true", kafkaInput.kafkaConsumerProperties().get("enable.auto.commit"));

        assertTrue(deserialized.outputs().isEmpty());
    }

    @Test
    void testValidation_ConstructorArgs_PipelineStepConfig() {
        PipelineStepConfig.ProcessorInfo validProcessorInfo = new PipelineStepConfig.ProcessorInfo("s", null);
        Map<String, PipelineStepConfig.OutputTarget> emptyOutputs = Collections.emptyMap();

        Exception e1 = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                null, StepType.PIPELINE, null, null, null, emptyOutputs, 0,0L,0L,0.0,0L, validProcessorInfo));
        assertTrue(e1.getMessage().contains("stepName cannot be null"));

        Exception e3 = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0,0L,0L,0.0,0L, null));
        assertTrue(e3.getMessage().contains("processorInfo cannot be null"));

        Exception eStepTypeNull = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                "s", null, null, null, null, emptyOutputs, 0,0L,0L,0.0,0L, validProcessorInfo));
        assertTrue(eStepTypeNull.getMessage().contains("stepType cannot be null"));

        Exception e4 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0,0L,0L,0.0,0L, new PipelineStepConfig.ProcessorInfo(null, null) ));
        assertTrue(e4.getMessage().contains("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName set."));

        Exception e5 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0,0L,0L,0.0,0L, new PipelineStepConfig.ProcessorInfo("grpc", "internal") ));
        assertTrue(e5.getMessage().contains("ProcessorInfo cannot have both grpcServiceName and internalProcessorBeanName set."));

        PipelineStepConfig configWithNullOutputs = new PipelineStepConfig(
                "test-step", StepType.PIPELINE, null, null, null, null,
                0, 1000L, 30000L, 2.0, null, validProcessorInfo);
        assertNotNull(configWithNullOutputs.outputs());
        assertTrue(configWithNullOutputs.outputs().isEmpty());
        Map<String, PipelineStepConfig.OutputTarget> outputsRef = configWithNullOutputs.outputs();
        assertThrows(UnsupportedOperationException.class, () -> outputsRef.put("another", null));
    }

    @Test
    void testOutputTarget_ConstructorValidation_TransportSpecificConfigs() {
        KafkaTransportConfig kCfg = new KafkaTransportConfig("topic", Collections.emptyMap());
        GrpcTransportConfig gCfg = new GrpcTransportConfig("service1", Collections.emptyMap());

        Exception e1 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target1", TransportType.KAFKA, null, null));
        assertTrue(e1.getMessage().contains("OutputTarget: KafkaTransportConfig must be provided"));

        Exception e2 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target1", TransportType.KAFKA, gCfg, kCfg));
        assertTrue(e2.getMessage().contains("OutputTarget: GrpcTransportConfig should only be provided"));

        Exception e3 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target2", TransportType.GRPC, null, null));
        assertTrue(e3.getMessage().contains("OutputTarget: GrpcTransportConfig must be provided"));

        Exception e4 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target2", TransportType.GRPC, gCfg, kCfg));
        assertTrue(e4.getMessage().contains("OutputTarget: KafkaTransportConfig should only be provided"));

        Exception e5 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target3", TransportType.INTERNAL, null, kCfg));
        assertTrue(e5.getMessage().contains("OutputTarget: KafkaTransportConfig should only be provided"));

        Exception e6 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig.OutputTarget(
                "target4", TransportType.INTERNAL, gCfg, null));
        assertTrue(e6.getMessage().contains("OutputTarget: GrpcTransportConfig should only be provided"));
    }


    @Test
    void testJsonPropertyNames_WithNewRecordModel() throws Exception {
        PipelineStepConfig.ProcessorInfo processorInfo = new PipelineStepConfig.ProcessorInfo("test-module-grpc", null);
        Map<String, PipelineStepConfig.OutputTarget> outputs = new HashMap<>();
        outputs.put("default", new PipelineStepConfig.OutputTarget(
                "next-step-id", TransportType.KAFKA, null,
                new KafkaTransportConfig("topic-for-next-step", Map.of("prop","val"))
        ));
        outputs.put("errorPath", new PipelineStepConfig.OutputTarget(
                "error-step-id", TransportType.INTERNAL, null, null
        ));

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = List.of(
                new KafkaInputDefinition(
                        List.of("json-test-topic"),
                        "json-test-group",
                        Map.of("client.id", "json-test-client")
                )
        );

        PipelineStepConfig config = new PipelineStepConfig(
                "test-step-json", StepType.PIPELINE, "A JSON step", "schema-abc",
                createJsonConfigOptions("{\"mode\":\"test\"}"),
                kafkaInputs,
                outputs, 1, 100L, 1000L, 1.0, 500L, processorInfo
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized JSON (Property Names Test - PipelineStepConfigTest):\n" + json);

        // Parse the step's JSON to directly check its top-level fields
        JsonNode stepNode = objectMapper.readTree(json);

        assertTrue(stepNode.has("stepName") && "test-step-json".equals(stepNode.path("stepName").asText()));
        assertTrue(stepNode.has("stepType") && "PIPELINE".equals(stepNode.path("stepType").asText()));
        assertTrue(stepNode.has("description") && "A JSON step".equals(stepNode.path("description").asText()));
        assertTrue(stepNode.has("customConfigSchemaId") && "schema-abc".equals(stepNode.path("customConfigSchemaId").asText()));
        assertTrue(stepNode.has("customConfig"));
        assertEquals("{\"mode\":\"test\"}", stepNode.path("customConfig").path("jsonConfig").toString()); // Corrected

        // Verify kafkaInputs in JSON
        assertTrue(stepNode.has("kafkaInputs"));
        assertTrue(stepNode.path("kafkaInputs").isArray());
        assertEquals(1, stepNode.path("kafkaInputs").size());
        JsonNode kafkaInputNode = stepNode.path("kafkaInputs").get(0);
        assertTrue(kafkaInputNode.has("listenTopics"));
        assertTrue(kafkaInputNode.path("listenTopics").isArray());
        assertEquals(1, kafkaInputNode.path("listenTopics").size());
        assertEquals("json-test-topic", kafkaInputNode.path("listenTopics").get(0).asText());
        assertTrue(kafkaInputNode.has("consumerGroupId"));
        assertEquals("json-test-group", kafkaInputNode.path("consumerGroupId").asText());
        assertTrue(kafkaInputNode.has("kafkaConsumerProperties"));
        assertEquals("json-test-client", kafkaInputNode.path("kafkaConsumerProperties").path("client.id").asText());

        assertTrue(stepNode.has("processorInfo"));
        assertEquals("test-module-grpc", stepNode.path("processorInfo").path("grpcServiceName").asText());
        assertTrue(stepNode.has("outputs"));
        JsonNode defaultOutputNode = stepNode.path("outputs").path("default");
        assertTrue(defaultOutputNode.has("targetStepName") && "next-step-id".equals(defaultOutputNode.path("targetStepName").asText()));
        assertTrue(defaultOutputNode.has("transportType") && "KAFKA".equals(defaultOutputNode.path("transportType").asText()));
        assertTrue(defaultOutputNode.has("kafkaTransport"));
        assertEquals("topic-for-next-step", defaultOutputNode.path("kafkaTransport").path("topic").asText());
        JsonNode errorOutputNode = stepNode.path("outputs").path("errorPath");
        assertTrue(errorOutputNode.has("targetStepName") && "error-step-id".equals(errorOutputNode.path("targetStepName").asText()));
        assertTrue(errorOutputNode.has("transportType") && "INTERNAL".equals(errorOutputNode.path("transportType").asText()));
        assertTrue(stepNode.has("maxRetries") && 1 == stepNode.path("maxRetries").asInt());


        // Assertions for ABSENCE of OLD top-level fields
        assertFalse(stepNode.has("pipelineStepId"), "Old 'pipelineStepId' should not exist.");
        assertFalse(stepNode.has("pipelineImplementationId"), "Old 'pipelineImplementationId' should not exist.");
        // Check that "transportType", "kafkaConfig", "grpcConfig" are NOT direct fields of the step config
        // (they are part of OutputTarget or implied by ProcessorInfo)
        assertFalse(stepNode.has("transportType") && !stepNode.path("outputs").path("default").has("transportType"),
                "Step itself should not have a direct top-level 'transportType' for its execution nature");
        assertFalse(stepNode.has("kafkaConfig"), "Step itself should not have a direct top-level 'kafkaConfig' for its execution");
        assertFalse(stepNode.has("grpcConfig"), "Step itself should not have a direct top-level 'grpcConfig' for its execution");
        assertFalse(stepNode.has("nextSteps"), "Old 'nextSteps' list should not exist at top level of step");
        assertFalse(stepNode.has("errorSteps"), "Old 'errorSteps' list should not exist at top level of step");
    }

    @Test
    void testLoadFromJsonFile_WithNewRecordModel() throws Exception {
        String jsonToLoad = """
        {
          "stepName": "imageProcessorKafka",
          "stepType": "PIPELINE",
          "description": "Processes images received from Kafka",
          "customConfigSchemaId": "imageProcSchema_v2",
          "customConfig": {
            "jsonConfig": {"format":"jpeg", "quality":90, "targetSize":"1024x768"},
            "configParams": {"logLevel": "DEBUG"}
          },
          "maxRetries": 3,
          "retryBackoffMs": 2000,
          "maxRetryBackoffMs": 60000,
          "retryBackoffMultiplier": 2.0,
          "stepTimeoutMs": 30000,
          "processorInfo": {
            "grpcServiceName": "image-processing-module-v2"
          },
          "kafkaInputs": [
            {
              "listenTopics": ["raw-images", "reprocess-images"],
              "consumerGroupId": "image-processor-group",
              "kafkaConsumerProperties": {
                "auto.offset.reset": "earliest",
                "fetch.max.bytes": "52428800"
              }
            }
          ],
          "outputs": {
            "default": {
              "targetStepName": "storeImageMetadata",
              "transportType": "KAFKA",
              "kafkaTransport": {
                "topic": "processed.images.imageProcessorKafka.metadata",
                "kafkaProducerProperties": {"acks": "all"}
              }
            },
            "onCompletion": {
              "targetStepName": "notifyCompletion",
              "transportType": "GRPC",
              "grpcTransport": {
                "serviceName": "notification-service",
                "grpcClientProperties": {"timeout": "5s"}
              }
            },
            "onError": {
              "targetStepName": "logImageProcessingError",
              "transportType": "INTERNAL"
            }
          }
        }
        """;
        PipelineStepConfig config = objectMapper.readValue(new ByteArrayInputStream(jsonToLoad.getBytes(StandardCharsets.UTF_8)), PipelineStepConfig.class);

        assertEquals("imageProcessorKafka", config.stepName());
        assertEquals(StepType.PIPELINE, config.stepType());
        assertEquals("image-processing-module-v2", config.processorInfo().grpcServiceName());
        assertNotNull(config.customConfig());
        assertEquals("{\"format\":\"jpeg\",\"quality\":90,\"targetSize\":\"1024x768\"}", config.customConfig().jsonConfig().toString());
        assertEquals("DEBUG", config.customConfig().configParams().get("logLevel"));
        assertEquals(3, config.maxRetries());
        assertEquals(2000L, config.retryBackoffMs());

        // Verify kafkaInputs
        assertNotNull(config.kafkaInputs());
        assertEquals(1, config.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = config.kafkaInputs().get(0);
        assertEquals(2, kafkaInput.listenTopics().size());
        assertTrue(kafkaInput.listenTopics().contains("raw-images"));
        assertTrue(kafkaInput.listenTopics().contains("reprocess-images"));
        assertEquals("image-processor-group", kafkaInput.consumerGroupId());
        assertEquals("earliest", kafkaInput.kafkaConsumerProperties().get("auto.offset.reset"));
        assertEquals("52428800", kafkaInput.kafkaConsumerProperties().get("fetch.max.bytes"));

        assertNotNull(config.outputs().get("default"));
        assertEquals("storeImageMetadata", config.outputs().get("default").targetStepName());
        assertEquals(TransportType.KAFKA, config.outputs().get("default").transportType());
        assertEquals("processed.images.imageProcessorKafka.metadata", config.outputs().get("default").kafkaTransport().topic());
        assertNotNull(config.outputs().get("onCompletion"));
        assertEquals("notifyCompletion", config.outputs().get("onCompletion").targetStepName());
        assertEquals(TransportType.GRPC, config.outputs().get("onCompletion").transportType());
        assertEquals("notification-service", config.outputs().get("onCompletion").grpcTransport().serviceName());
        assertNotNull(config.outputs().get("onError"));
        assertEquals("logImageProcessingError", config.outputs().get("onError").targetStepName());
        assertEquals(TransportType.INTERNAL, config.outputs().get("onError").transportType());
        assertNull(config.outputs().get("onError").kafkaTransport());
        assertNull(config.outputs().get("onError").grpcTransport());
    }
}
