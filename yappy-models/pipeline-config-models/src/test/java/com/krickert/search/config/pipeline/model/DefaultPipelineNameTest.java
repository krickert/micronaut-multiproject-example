package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the defaultPipelineName field in PipelineClusterConfig.
 */
class DefaultPipelineNameTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new ParameterNamesModule()); // For record deserialization
        objectMapper.registerModule(new Jdk8Module());           // For readable JSON output
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Test
    void testDefaultPipelineNameField() {
        // Create a PipelineClusterConfig with a defaultPipelineName
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                null, // pipelineGraphConfig
                null, // pipelineModuleMap
                "default-pipeline", // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet() // allowedGrpcServices
        );

        // Verify that defaultPipelineName is set correctly
        assertEquals("default-pipeline", config.defaultPipelineName());
    }

    @Test
    void testNullDefaultPipelineName() {
        // Create a PipelineClusterConfig with a null defaultPipelineName
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                null, // pipelineGraphConfig
                null, // pipelineModuleMap
                null, // defaultPipelineName
                Collections.emptySet(), // allowedKafkaTopics
                Collections.emptySet() // allowedGrpcServices
        );

        // Verify that defaultPipelineName is null
        assertNull(config.defaultPipelineName());
    }

    @Test
    void testSerializationDeserialization() throws Exception {
        // Create a PipelineClusterConfig with a defaultPipelineName
        PipelineClusterConfig config = new PipelineClusterConfig(
                "test-cluster",
                null, // pipelineGraphConfig
                null, // pipelineModuleMap
                "default-pipeline", // defaultPipelineName
                Set.of("topic1"), // allowedKafkaTopics
                Set.of("service1") // allowedGrpcServices
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(config);
        System.out.println("Serialized PipelineClusterConfig with defaultPipelineName JSON:\n" + json);

        // Verify that defaultPipelineName is included in the JSON
        assertTrue(json.contains("\"defaultPipelineName\"") && json.contains("\"default-pipeline\""),
                "JSON should include defaultPipelineName field with value default-pipeline");

        // Deserialize from JSON
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        // Verify that defaultPipelineName is correctly deserialized
        assertEquals("default-pipeline", deserialized.defaultPipelineName());
    }

    @Test
    void testDeserializationWithMissingDefaultPipelineName() throws Exception {
        // Create JSON without defaultPipelineName field
        String json = "{\n" +
                "  \"clusterName\": \"test-cluster\",\n" +
                "  \"allowedKafkaTopics\": [\"topic1\"],\n" +
                "  \"allowedGrpcServices\": [\"service1\"]\n" +
                "}";

        // Deserialize from JSON
        PipelineClusterConfig deserialized = objectMapper.readValue(json, PipelineClusterConfig.class);

        // Verify that defaultPipelineName is null when missing from JSON
        assertNull(deserialized.defaultPipelineName());
    }
}