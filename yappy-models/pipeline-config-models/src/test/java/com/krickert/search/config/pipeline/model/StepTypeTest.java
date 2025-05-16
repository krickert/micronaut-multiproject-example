package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the StepType enum and its integration with PipelineStepConfig.
 */
class StepTypeTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule()); // For record deserialization
        objectMapper.registerModule(new Jdk8Module());           // For readable JSON output
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Test
    void testStepTypeDefaultValue() {
        // Create a PipelineStepConfig with null stepType
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step",
                "test-module",
                null, // customConfig
                Collections.emptyList(), // nextSteps
                Collections.emptyList(), // errorSteps
                TransportType.INTERNAL,
                null, // kafkaConfig
                null, // grpcConfig
                null  // stepType - should default to PIPELINE
        );

        // Verify that stepType defaults to PIPELINE
        assertEquals(StepType.PIPELINE, config.stepType());
    }

    @Test
    void testStepTypeExplicitValues() {
        // Create PipelineStepConfig instances with explicit StepType values
        PipelineStepConfig pipelineStep = new PipelineStepConfig(
                "pipeline-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.PIPELINE
        );

        PipelineStepConfig initialStep = new PipelineStepConfig(
                "initial-step",
                "test-module",
                null,
                List.of("next-step"),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.INITIAL_PIPELINE
        );

        PipelineStepConfig sinkStep = new PipelineStepConfig(
                "sink-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.SINK
        );

        // Verify that stepType values are set correctly
        assertEquals(StepType.PIPELINE, pipelineStep.stepType());
        assertEquals(StepType.INITIAL_PIPELINE, initialStep.stepType());
        assertEquals(StepType.SINK, sinkStep.stepType());
    }

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a PipelineStepConfig with explicit StepType
        PipelineStepConfig config = new PipelineStepConfig(
                "test-step",
                "test-module",
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                TransportType.INTERNAL,
                null,
                null,
                StepType.INITIAL_PIPELINE
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineStepConfig with StepType JSON:\n" + json);

        // Verify that stepType is included in the JSON
        assertTrue(json.contains("\"stepType\"") && json.contains("\"INITIAL_PIPELINE\""),
                "JSON should include stepType field with value INITIAL_PIPELINE");

        // Deserialize from JSON
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        // Verify that stepType is correctly deserialized
        assertEquals(StepType.INITIAL_PIPELINE, deserialized.stepType());
    }

    @Test
    void testDeserializationWithMissingStepType() throws Exception {
        // Create JSON without stepType field
        String json = "{\n" +
                "  \"pipelineStepId\": \"test-step\",\n" +
                "  \"pipelineImplementationId\": \"test-module\",\n" +
                "  \"nextSteps\": [],\n" +
                "  \"errorSteps\": [],\n" +
                "  \"transportType\": \"INTERNAL\"\n" +
                "}";

        // Deserialize from JSON
        PipelineStepConfig deserialized = objectMapper.readValue(json, PipelineStepConfig.class);

        // Verify that stepType defaults to PIPELINE when missing from JSON
        assertEquals(StepType.PIPELINE, deserialized.stepType());
    }
}