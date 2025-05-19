package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PipelineStepConfigTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = PipelineConfigTestUtils.createObjectMapper();
    }

    // Helper to create JsonConfigOptions from a JSON string
    private PipelineStepConfig.JsonConfigOptions createJsonConfigOptions(String jsonString) throws com.fasterxml.jackson.core.JsonProcessingException {
        return PipelineConfigTestUtils.createJsonConfigOptions(jsonString);
    }


    @Test
    void testSerializationDeserialization_KafkaOutputStep() throws Exception {
        // Get the search indexing pipeline JSON from the test utilities
        String clusterJson = SamplePipelineConfigJson.getSearchIndexingPipelineJson();

        // Extract the file connector step from the cluster configuration
        String stepJson = extractPipelineStepJson(clusterJson, "search-indexing-pipeline", "file-connector");

        // Deserialize the JSON to a PipelineStepConfig object
        PipelineStepConfig config = PipelineConfigTestUtils.fromJson(stepJson, PipelineStepConfig.class);

        // Serialize to JSON using the test utilities
        String json = PipelineConfigTestUtils.toJson(config);
        System.out.println("Serialized KAFKA Output PipelineStepConfig JSON:\n" + json);

        // Deserialize back to object using the test utilities
        PipelineStepConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineStepConfig.class);

        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);

        // Verify specific properties
        assertEquals("file-connector", deserialized.stepName());
        assertEquals(StepType.INITIAL_PIPELINE, deserialized.stepType());
        assertEquals("Monitors a directory for new files to index", deserialized.description());
        assertEquals("file-connector-schema", deserialized.customConfigSchemaId());

        // Verify custom config
        assertNotNull(deserialized.customConfig());
        assertTrue(deserialized.customConfig().jsonConfig().has("directory"));
        assertTrue(deserialized.customConfig().jsonConfig().has("pollingIntervalMs"));
        assertTrue(deserialized.customConfig().jsonConfig().has("filePatterns"));

        // Verify processor info
        assertNotNull(deserialized.processorInfo());
        assertEquals("file-connector-service", deserialized.processorInfo().grpcServiceName());
        assertNull(deserialized.processorInfo().internalProcessorBeanName());

        // Verify outputs
        assertNotNull(deserialized.outputs());
        assertEquals(1, deserialized.outputs().size());

        // Verify default output
        PipelineStepConfig.OutputTarget defaultOutput = deserialized.outputs().get("default");
        assertNotNull(defaultOutput);
        assertEquals("document-parser", defaultOutput.targetStepName());
        assertEquals(TransportType.KAFKA, defaultOutput.transportType());
        assertNotNull(defaultOutput.kafkaTransport());
        assertEquals("search.files.incoming", defaultOutput.kafkaTransport().topic());
        assertNull(defaultOutput.grpcTransport());
    }

    @Test
    void testSerializationDeserialization_GrpcOutputStep() throws Exception {
        // Get the comprehensive pipeline cluster JSON from the test utilities
        String clusterJson = SamplePipelineConfigJson.getComprehensivePipelineClusterConfigJson();

        // Extract the analytics processor step from the cluster configuration
        // This step uses gRPC for output
        String stepJson = extractPipelineStepJson(clusterJson, "analytics-pipeline", "analytics-processor");

        // Deserialize the JSON to a PipelineStepConfig object
        PipelineStepConfig config = PipelineConfigTestUtils.fromJson(stepJson, PipelineStepConfig.class);

        // Serialize to JSON using the test utilities
        String json = PipelineConfigTestUtils.toJson(config);
        System.out.println("Serialized GRPC Output PipelineStepConfig JSON:\n" + json);

        // Deserialize back to object using the test utilities
        PipelineStepConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineStepConfig.class);

        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);

        // Verify specific properties
        assertEquals("analytics-processor", deserialized.stepName());
        assertEquals(StepType.PIPELINE, deserialized.stepType());
        assertEquals("Processes documents for analytics", deserialized.description());
        assertEquals("analytics-processor-schema", deserialized.customConfigSchemaId());

        // Verify custom config
        assertNotNull(deserialized.customConfig());
        assertTrue(deserialized.customConfig().jsonConfig().has("aggregationEnabled"));
        assertTrue(deserialized.customConfig().jsonConfig().has("trendAnalysisEnabled"));

        // Verify processor info
        assertNotNull(deserialized.processorInfo());
        assertEquals("analytics-processor-service", deserialized.processorInfo().grpcServiceName());
        assertNull(deserialized.processorInfo().internalProcessorBeanName());

        // Verify kafka inputs
        assertNotNull(deserialized.kafkaInputs());
        assertEquals(1, deserialized.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = deserialized.kafkaInputs().get(0);
        assertTrue(kafkaInput.listenTopics().contains("document.for.analytics"));
        assertEquals("analytics-processor-group", kafkaInput.consumerGroupId());

        // Verify outputs
        assertNotNull(deserialized.outputs());
        assertEquals(1, deserialized.outputs().size());

        // Verify default output
        PipelineStepConfig.OutputTarget defaultOutput = deserialized.outputs().get("default_dashboard");
        assertNotNull(defaultOutput);
        assertEquals("dashboard-updater", defaultOutput.targetStepName());
        assertEquals(TransportType.GRPC, defaultOutput.transportType());
        assertNotNull(defaultOutput.grpcTransport());
        assertEquals("dashboard-service", defaultOutput.grpcTransport().serviceName());
        assertNull(defaultOutput.kafkaTransport());
    }

    @Test
    void testSerializationDeserialization_InternalStepAsSink() throws Exception {
        // Get the search indexing pipeline JSON from the test utilities
        String clusterJson = SamplePipelineConfigJson.getSearchIndexingPipelineJson();

        // Extract the search indexer step from the cluster configuration
        // This step is a sink
        String stepJson = extractPipelineStepJson(clusterJson, "search-indexing-pipeline", "search-indexer");

        // Deserialize the JSON to a PipelineStepConfig object
        PipelineStepConfig config = PipelineConfigTestUtils.fromJson(stepJson, PipelineStepConfig.class);

        // Serialize to JSON using the test utilities
        String json = PipelineConfigTestUtils.toJson(config);
        System.out.println("Serialized Sink PipelineStepConfig JSON:\n" + json);

        // Deserialize back to object using the test utilities
        PipelineStepConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineStepConfig.class);

        // Verify that the deserialized object equals the original
        assertEquals(config, deserialized);

        // Verify specific properties
        assertEquals("search-indexer", deserialized.stepName());
        assertEquals(StepType.SINK, deserialized.stepType());
        assertEquals("Indexes documents in the search engine", deserialized.description());
        assertEquals("search-indexer-schema", deserialized.customConfigSchemaId());

        // Verify custom config
        assertNotNull(deserialized.customConfig());
        assertTrue(deserialized.customConfig().jsonConfig().has("indexName"));
        assertTrue(deserialized.customConfig().jsonConfig().has("batchSize"));

        // Verify processor info
        assertNotNull(deserialized.processorInfo());
        assertEquals("search-indexer-service", deserialized.processorInfo().grpcServiceName());
        assertNull(deserialized.processorInfo().internalProcessorBeanName());

        // Verify kafka inputs
        assertNotNull(deserialized.kafkaInputs());
        assertEquals(1, deserialized.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = deserialized.kafkaInputs().get(0);
        assertTrue(kafkaInput.listenTopics().contains("search.documents.analyzed"));
        assertEquals("search-indexer-group", kafkaInput.consumerGroupId());

        // Verify outputs
        assertNotNull(deserialized.outputs());
        assertEquals(1, deserialized.outputs().size());

        // Verify error output
        PipelineStepConfig.OutputTarget errorOutput = deserialized.outputs().get("onError");
        assertNotNull(errorOutput);
        assertEquals("error-handler", errorOutput.targetStepName());
        assertEquals(TransportType.KAFKA, errorOutput.transportType());
        assertEquals("search.errors", errorOutput.kafkaTransport().topic());
    }

    @Test
    void testValidation_ConstructorArgs_PipelineStepConfig() {
        PipelineStepConfig.ProcessorInfo validProcessorInfo = new PipelineStepConfig.ProcessorInfo("s", null);
        Map<String, PipelineStepConfig.OutputTarget> emptyOutputs = Collections.emptyMap();

        Exception e1 = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                null, StepType.PIPELINE, null, null, null, emptyOutputs, 0, 0L, 0L, 0.0, 0L, validProcessorInfo));
        assertTrue(e1.getMessage().contains("stepName cannot be null"));

        Exception e3 = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0, 0L, 0L, 0.0, 0L, null));
        assertTrue(e3.getMessage().contains("processorInfo cannot be null"));

        Exception eStepTypeNull = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                "s", null, null, null, null, emptyOutputs, 0, 0L, 0L, 0.0, 0L, validProcessorInfo));
        assertTrue(eStepTypeNull.getMessage().contains("stepType cannot be null"));

        Exception e4 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0, 0L, 0L, 0.0, 0L, new PipelineStepConfig.ProcessorInfo(null, null)));
        assertTrue(e4.getMessage().contains("ProcessorInfo must have either grpcServiceName or internalProcessorBeanName set."));

        Exception e5 = assertThrows(IllegalArgumentException.class, () -> new PipelineStepConfig(
                "s", StepType.PIPELINE, null, null, null, emptyOutputs, 0, 0L, 0L, 0.0, 0L, new PipelineStepConfig.ProcessorInfo("grpc", "internal")));
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
                new KafkaTransportConfig("topic-for-next-step", Map.of("prop", "val"))
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
        // Get the search indexing pipeline JSON from the test utilities
        String clusterJson = SamplePipelineConfigJson.getSearchIndexingPipelineJson();

        // Extract a pipeline step from the cluster configuration
        String stepJson = extractPipelineStepJson(clusterJson, "search-indexing-pipeline", "document-parser");

        // Deserialize the JSON to a PipelineStepConfig object
        PipelineStepConfig config = PipelineConfigTestUtils.fromJson(stepJson, PipelineStepConfig.class);

        // Verify the step properties
        assertEquals("document-parser", config.stepName());
        assertEquals(StepType.PIPELINE, config.stepType());
        assertEquals("document-parser-service", config.processorInfo().grpcServiceName());

        // Verify custom config
        assertNotNull(config.customConfig());
        assertTrue(config.customConfig().jsonConfig().has("extractMetadata"));
        assertTrue(config.customConfig().jsonConfig().has("extractText"));
        assertEquals("document-parser-schema", config.customConfigSchemaId());

        // Verify kafka inputs
        assertNotNull(config.kafkaInputs());
        assertEquals(1, config.kafkaInputs().size());
        KafkaInputDefinition kafkaInput = config.kafkaInputs().get(0);
        assertTrue(kafkaInput.listenTopics().contains("search.files.incoming"));
        assertEquals("document-parser-group", kafkaInput.consumerGroupId());

        // Verify outputs
        assertNotNull(config.outputs());
        assertEquals(2, config.outputs().size());

        // Verify default output
        assertNotNull(config.outputs().get("default"));
        assertEquals("text-analyzer", config.outputs().get("default").targetStepName());
        assertEquals(TransportType.KAFKA, config.outputs().get("default").transportType());
        assertEquals("search.documents.parsed", config.outputs().get("default").kafkaTransport().topic());

        // Verify error output
        assertNotNull(config.outputs().get("onError"));
        assertEquals("error-handler", config.outputs().get("onError").targetStepName());
        assertEquals(TransportType.KAFKA, config.outputs().get("onError").transportType());
        assertEquals("search.errors", config.outputs().get("onError").kafkaTransport().topic());
    }

    /**
     * Helper method to extract a pipeline step configuration from a cluster configuration JSON.
     */
    private String extractPipelineStepJson(String clusterJson, String pipelineName, String stepName) throws Exception {
        // Parse the cluster JSON
        JsonNode clusterNode = objectMapper.readTree(clusterJson);

        // Extract the pipeline step node
        JsonNode stepNode = clusterNode
                .path("pipelineGraphConfig")
                .path("pipelines")
                .path(pipelineName)
                .path("pipelineSteps")
                .path(stepName);

        // Convert the step node back to JSON
        return objectMapper.writeValueAsString(stepNode);
    }
}
