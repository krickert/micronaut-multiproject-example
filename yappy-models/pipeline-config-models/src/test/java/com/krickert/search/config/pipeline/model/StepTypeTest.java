package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException; // Import the correct exception
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StepType enum and its integration with PipelineStepConfig.
 */
class StepTypeTest {

    private ObjectMapper objectMapper;

    // Helper to create a minimal valid ProcessorInfo for tests
    private PipelineStepConfig.ProcessorInfo createDummyProcessorInfo() {
        // Assuming ProcessorInfo has a constructor like: ProcessorInfo(String grpcServiceName, String internalProcessorBeanName)
        // And one of them must be non-null, and not both.
        return new PipelineStepConfig.ProcessorInfo("dummyService", null);
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


    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule()); // For record deserialization
        objectMapper.registerModule(new Jdk8Module());           // For Optional, etc.
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT); // For readable JSON output
    }

    @Test
    void testStepTypeDefaultValueInConstructor() {
        // The new PipelineStepConfig record constructor *requires* a non-null StepType.
        // The old behavior of defaulting a null stepType to PIPELINE inside the constructor
        // has been removed in favor of explicit non-null requirement.
        // Thus, this test's original intent (testing a null input defaulting) changes
        // to testing that a null stepType is not allowed by the constructor.

        Exception e = assertThrows(NullPointerException.class, () -> new PipelineStepConfig(
                "test-step",
                null, // stepType is null
                null, // description
                null, // customConfigSchemaId
                null, // customConfig
                Collections.emptyMap(), // outputs
                0, 1000L, 30000L, 2.0, null, // retry & timeout defaults
                createDummyProcessorInfo()
        ));
        assertTrue(e.getMessage().contains("stepType cannot be null"));
    }

    @Test
    void testStepTypeExplicitValues() {
        PipelineStepConfig.ProcessorInfo processorInfo = createDummyProcessorInfo();
        Map<String, PipelineStepConfig.OutputTarget> emptyOutputs = Collections.emptyMap();
        Map<String, PipelineStepConfig.OutputTarget> initialOutputs = Map.of(
                "default", new PipelineStepConfig.OutputTarget("next-step", TransportType.INTERNAL, null, null)
        );


        PipelineStepConfig pipelineStep = new PipelineStepConfig(
                "pipeline-step", StepType.PIPELINE, null, null, null,
                emptyOutputs, 0,1L,1L,1.0,0L, processorInfo
        );

        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step", StepType.INITIAL_PIPELINE, null, null, null,
                initialOutputs, // Must have outputs
                0,1L,1L,1.0,0L, processorInfo
        );

        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step", StepType.SINK, null, null, null,
                emptyOutputs, // Must have no outputs
                0,1L,1L,1.0,0L, processorInfo
        );

        assertEquals(StepType.PIPELINE, pipelineStep.stepType());
        assertEquals(StepType.INITIAL_PIPELINE, initialStep.stepType());
        assertEquals(StepType.SINK, sinkStep.stepType());
    }

    @Test
    void testSerializationDeserialization() throws Exception {
        PipelineStepConfig.ProcessorInfo processorInfo = createDummyProcessorInfo();
        Map<String, PipelineStepConfig.OutputTarget> outputs = Map.of(
                "default", new PipelineStepConfig.OutputTarget("next", TransportType.INTERNAL, null, null)
        );


        PipelineStepConfig config = new PipelineStepConfig(
                "test-step",
                StepType.INITIAL_PIPELINE, // Explicit StepType
                "description", "schemaId",
                createJsonConfigOptions("{\"cfgKey\":\"cfgVal\"}"),
                outputs,
                1, 2000L, 60000L, 1.5, 5000L,
                processorInfo
        );

        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineStepConfig with StepType JSON:\n" + json);

        assertTrue(json.contains("\"stepType\" : \"INITIAL_PIPELINE\""),
                "JSON should include stepType field with value INITIAL_PIPELINE");

        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);
        assertEquals(StepType.INITIAL_PIPELINE, deserialized.stepType());
        assertEquals("test-step", deserialized.stepName());
        assertNotNull(deserialized.processorInfo());
        assertEquals("dummyService", deserialized.processorInfo().grpcServiceName());
        assertNotNull(deserialized.outputs().get("default"));
    }

    @Test
    void testDeserializationWithMissingStepType() throws Exception {
        // JSON reflecting the NEW PipelineStepConfig structure, but omitting stepType
        // It also needs other required fields like stepName and processorInfo.
        String json = """
        {
          "stepName": "test-step-missing-type",
          "description": "A step description",
          "customConfigSchemaId": "some-schema",
          "customConfig": { "jsonConfig": {"mode":"test"}, "configParams": {} },
          "outputs": {
            "default": {
              "targetStepName": "next-target",
              "transportType": "INTERNAL"
            }
          },
          "maxRetries": 0,
          "retryBackoffMs": 1000,
          "maxRetryBackoffMs": 30000,
          "retryBackoffMultiplier": 2.0,
          "processorInfo": { "internalProcessorBeanName": "test-module" }
        }
        """;
        // The @JsonCreator constructor for PipelineStepConfig requires stepType to be non-null due to Objects.requireNonNull.
        // Jackson will fail to deserialize if a non-nullable constructor argument is missing from JSON.
        // This results in a ValueInstantiationException, caused by the NullPointerException.

        Exception e = assertThrows(ValueInstantiationException.class, () -> { // Changed expected exception
            objectMapper.readValue(json, PipelineStepConfig.class);
        });
        // The exact message might vary based on Jackson version, but the cause is the NPE for stepType.
        assertTrue(e.getMessage().contains("stepType cannot be null") || e.getMessage().contains("problem: stepType cannot be null"),
                "Error message should mention 'stepType cannot be null'. Actual: " + e.getMessage());
    }
}