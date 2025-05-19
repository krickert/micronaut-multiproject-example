package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.krickert.search.config.pipeline.model.test.PipelineConfigTestUtils;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigJson;
import com.krickert.search.config.pipeline.model.test.SamplePipelineConfigObjects;
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

        // Create a list of KafkaInputDefinition for testing
        List<KafkaInputDefinition> kafkaInputs = Collections.emptyList();
        if (processorNatureOld == TransportType.KAFKA) {
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
                newStepType == null ? com.krickert.search.config.pipeline.model.StepType.PIPELINE : newStepType,
                "Description for " + stepName,
                "schema-for-" + moduleImplementationId,
                customConfig, // Pass JsonConfigOptions directly
                kafkaInputs,
                outputs,
                0, 1000L, 30000L, 2.0, null,
                processorInfo
        );
    }


    @Test
    void testSerializationDeserialization_WithKafkaAndGrpcSteps() throws Exception {
        // Get the search indexing pipeline from the sample objects
        PipelineClusterConfig clusterConfig = SamplePipelineConfigObjects.createSearchIndexingPipelineClusterConfig();

        // Extract the pipeline from the cluster config
        PipelineConfig pipelineConfig = clusterConfig.pipelineGraphConfig().pipelines().get("search-indexing-pipeline");

        // Serialize to JSON using the test utilities
        String json = PipelineConfigTestUtils.toJson(pipelineConfig);
        System.out.println("Serialized PipelineConfig JSON (New Model):\n" + json);

        // Deserialize back to object using the test utilities
        PipelineConfig deserialized = PipelineConfigTestUtils.fromJson(json, PipelineConfig.class);

        // Verify that the deserialized object equals the original
        assertEquals(pipelineConfig, deserialized);

        // Verify specific properties
        assertEquals("search-indexing-pipeline", deserialized.name());
        assertNotNull(deserialized.pipelineSteps());
        assertEquals(5, deserialized.pipelineSteps().size());

        // Verify file connector step
        PipelineStepConfig fileConnectorStep = deserialized.pipelineSteps().get("file-connector");
        assertNotNull(fileConnectorStep);
        assertEquals("file-connector", fileConnectorStep.stepName());
        assertEquals(StepType.INITIAL_PIPELINE, fileConnectorStep.stepType());
        assertEquals("file-connector-service", fileConnectorStep.processorInfo().grpcServiceName());

        // Verify document parser step
        PipelineStepConfig documentParserStep = deserialized.pipelineSteps().get("document-parser");
        assertNotNull(documentParserStep);
        assertEquals("document-parser", documentParserStep.stepName());
        assertEquals(StepType.PIPELINE, documentParserStep.stepType());
        assertEquals("document-parser-service", documentParserStep.processorInfo().grpcServiceName());

        // Verify text analyzer step
        PipelineStepConfig textAnalyzerStep = deserialized.pipelineSteps().get("text-analyzer");
        assertNotNull(textAnalyzerStep);
        assertEquals("text-analyzer", textAnalyzerStep.stepName());
        assertEquals(StepType.PIPELINE, textAnalyzerStep.stepType());
        assertEquals("text-analyzer-service", textAnalyzerStep.processorInfo().grpcServiceName());

        // Verify search indexer step
        PipelineStepConfig searchIndexerStep = deserialized.pipelineSteps().get("search-indexer");
        assertNotNull(searchIndexerStep);
        assertEquals("search-indexer", searchIndexerStep.stepName());
        assertEquals(StepType.SINK, searchIndexerStep.stepType());
        assertEquals("search-indexer-service", searchIndexerStep.processorInfo().grpcServiceName());

        // Verify error handler step
        PipelineStepConfig errorHandlerStep = deserialized.pipelineSteps().get("error-handler");
        assertNotNull(errorHandlerStep);
        assertEquals("error-handler", errorHandlerStep.stepName());
        assertEquals(StepType.SINK, errorHandlerStep.stepType());
        assertEquals("error-handler-service", errorHandlerStep.processorInfo().grpcServiceName());
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
        // Get the search indexing pipeline JSON from the test utilities
        String jsonToLoad = SamplePipelineConfigJson.getSearchIndexingPipelineJson();

        // Extract the pipeline configuration from the cluster configuration
        String pipelineJson = extractPipelineJson(jsonToLoad, "search-indexing-pipeline");

        // Deserialize the JSON to a PipelineConfig object
        PipelineConfig config = PipelineConfigTestUtils.fromJson(pipelineJson, PipelineConfig.class);

        // Verify the pipeline properties
        assertEquals("search-indexing-pipeline", config.name());
        assertNotNull(config.pipelineSteps());
        assertEquals(5, config.pipelineSteps().size());

        // Verify the file connector step
        PipelineStepConfig fileConnectorStep = config.pipelineSteps().get("file-connector");
        assertNotNull(fileConnectorStep);
        assertEquals("file-connector-service", fileConnectorStep.processorInfo().grpcServiceName());
        assertNotNull(fileConnectorStep.outputs().get("default"));
        assertEquals("document-parser", fileConnectorStep.outputs().get("default").targetStepName());
        assertEquals(TransportType.KAFKA, fileConnectorStep.outputs().get("default").transportType());
        assertEquals("search.files.incoming", fileConnectorStep.outputs().get("default").kafkaTransport().topic());
        assertEquals(StepType.INITIAL_PIPELINE, fileConnectorStep.stepType());

        // Verify the document parser step
        PipelineStepConfig documentParserStep = config.pipelineSteps().get("document-parser");
        assertNotNull(documentParserStep);
        assertEquals("document-parser-service", documentParserStep.processorInfo().grpcServiceName());
        assertNotNull(documentParserStep.customConfig().jsonConfig());
        assertTrue(documentParserStep.customConfig().jsonConfig().has("extractMetadata"));
        assertEquals("document-parser-schema", documentParserStep.customConfigSchemaId());
        assertEquals("text-analyzer", documentParserStep.outputs().get("default").targetStepName());

        // Verify the search indexer step (sink)
        PipelineStepConfig searchIndexerStep = config.pipelineSteps().get("search-indexer");
        assertNotNull(searchIndexerStep);
        assertEquals(StepType.SINK, searchIndexerStep.stepType());
        assertEquals("search-indexer-service", searchIndexerStep.processorInfo().grpcServiceName());
    }

    /**
     * Helper method to extract a pipeline configuration from a cluster configuration JSON.
     */
    private String extractPipelineJson(String clusterJson, String pipelineName) throws Exception {
        // Parse the cluster JSON
        JsonNode clusterNode = objectMapper.readTree(clusterJson);

        // Extract the pipeline node
        JsonNode pipelineNode = clusterNode
            .path("pipelineGraphConfig")
            .path("pipelines")
            .path(pipelineName);

        // Convert the pipeline node back to JSON
        return objectMapper.writeValueAsString(pipelineNode);
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
