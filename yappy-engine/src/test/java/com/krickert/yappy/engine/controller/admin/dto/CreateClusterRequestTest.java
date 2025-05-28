package com.krickert.yappy.engine.controller.admin.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CreateClusterRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testSerializationAndDeserialization() throws Exception {
        // Create module inputs
        List<PipelineModuleInput> modules = Arrays.asList(
                new PipelineModuleInput("module1", "Module One"),
                new PipelineModuleInput("module2", "Module Two")
        );

        // Create an instance with all fields populated
        CreateClusterRequest original = new CreateClusterRequest(
                "test-cluster",
                "first-pipeline",
                modules
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        CreateClusterRequest deserialized = objectMapper.readValue(json, CreateClusterRequest.class);

        // Verify all fields match
        assertEquals(original.getClusterName(), deserialized.getClusterName());
        assertEquals(original.getFirstPipelineName(), deserialized.getFirstPipelineName());
        assertEquals(original.getInitialModules().size(), deserialized.getInitialModules().size());
        assertEquals(original.getInitialModules().getFirst().getImplementationId(), 
                     deserialized.getInitialModules().getFirst().getImplementationId());
        assertEquals(original.getInitialModules().getFirst().getImplementationName(), 
                     deserialized.getInitialModules().getFirst().getImplementationName());
    }

    @Test
    void testSerializationWithNullFields() throws Exception {
        // Create an instance with some null fields
        CreateClusterRequest original = new CreateClusterRequest(
                "test-cluster",
                null,
                null
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        CreateClusterRequest deserialized = objectMapper.readValue(json, CreateClusterRequest.class);

        // Verify all fields match
        assertEquals(original.getClusterName(), deserialized.getClusterName());
        assertNull(deserialized.getFirstPipelineName());
        assertNull(deserialized.getInitialModules());
    }

    @Test
    void testSerializationWithEmptyList() throws Exception {
        // Create an instance with empty list
        CreateClusterRequest original = new CreateClusterRequest(
                "test-cluster",
                "first-pipeline",
                Collections.emptyList()
        );

        // Serialize to JSON
        String json = objectMapper.writeValueAsString(original);

        // Deserialize back to object
        CreateClusterRequest deserialized = objectMapper.readValue(json, CreateClusterRequest.class);

        // Verify all fields match
        assertEquals(original.getClusterName(), deserialized.getClusterName());
        assertEquals(original.getFirstPipelineName(), deserialized.getFirstPipelineName());
        assertEquals(0, deserialized.getInitialModules().size());
    }

    @Test
    void testDeserializationWithMissingFields() throws Exception {
        // JSON with missing fields
        String json = "{\"clusterName\":\"test-cluster\"}";

        // Deserialize to object
        CreateClusterRequest deserialized = objectMapper.readValue(json, CreateClusterRequest.class);

        // Verify fields
        assertEquals("test-cluster", deserialized.getClusterName());
        assertNull(deserialized.getFirstPipelineName());
        assertNull(deserialized.getInitialModules());
    }
}