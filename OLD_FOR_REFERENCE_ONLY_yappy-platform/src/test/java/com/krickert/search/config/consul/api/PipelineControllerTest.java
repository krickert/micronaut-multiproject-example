package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.model.CreatePipelineRequest;
import com.krickert.search.config.consul.model.PipelineConfig;
import com.krickert.search.config.consul.model.PipelineConfigDto;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.KeyValueClient;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineControllerTest implements TestPropertyProvider {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ConsulKvService consulKvService;

    @Inject
    KeyValueClient keyValueClient;

    @Inject
    PipelineConfig pipelineConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

        // Disable data seeding for tests
        properties.put("consul.data.seeding.enabled", "false");
        properties.put("consul.client.registration.enabled", "false");
        properties.put("consul.client.watch.enabled", "false");

        return properties;
    }

    @BeforeEach
    void setUp() {
        // Reset the PipelineConfig state
        pipelineConfig.reset();

        // Clear any existing pipeline configurations
        String configPath = consulKvService.getFullPath("pipeline.configs");
        keyValueClient.deleteKeys(configPath);
    }

    @Test
    void testListPipelinesEmpty() {
        // Delete all existing pipelines to ensure the list is empty
        try {
            // Get all pipelines as a raw string
            HttpResponse<String> listResponse = client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines"), String.class);

            if (listResponse.body() != null) {
                // Parse the JSON array manually
                String body = listResponse.body();
                if (body.startsWith("[") && body.endsWith("]")) {
                    // Remove the brackets and split by commas
                    String content = body.substring(1, body.length() - 1);
                    if (!content.trim().isEmpty()) {
                        String[] pipelineNames = content.split(",");
                        for (String pipelineName : pipelineNames) {
                            // Remove quotes and trim
                            pipelineName = pipelineName.trim();
                            if (pipelineName.startsWith("\"") && pipelineName.endsWith("\"")) {
                                pipelineName = pipelineName.substring(1, pipelineName.length() - 1);
                            }
                            try {
                                client.toBlocking().exchange(
                                        HttpRequest.DELETE("/api/pipelines/" + pipelineName));
                            } catch (Exception e) {
                                // Ignore errors when deleting pipelines
                                System.out.println("Error deleting pipeline: " + pipelineName + " - " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Ignore errors when listing pipelines
            System.out.println("Error listing pipelines: " + e.getMessage());
        }

        // Now test listing pipelines when none exist
        HttpResponse<String> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines"), String.class);

        assertEquals(HttpStatus.OK, response.status());
        assertNotNull(response.body());
        // Check if the response is an empty JSON array
        assertTrue(response.body().equals("[]") || response.body().equals("[ ]"));
    }

    @Test
    void testCreatePipeline() {
        // Create a new pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("test-pipeline");

        HttpResponse<PipelineConfigDto> response = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, response.status());
        assertNotNull(response.body());
        assertEquals("test-pipeline", response.body().getName());
        assertEquals(1, response.body().getPipelineVersion());
        assertTrue(response.body().getServices().isEmpty());
    }

    @Test
    void testCreatePipelineWithInvalidName() {
        // Test creating a pipeline with an empty name
        CreatePipelineRequest requestBody = new CreatePipelineRequest("");

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/pipelines", requestBody)
                            .contentType(MediaType.APPLICATION_JSON),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testCreatePipelineWithMissingName() {
        // Test creating a pipeline without a name field
        CreatePipelineRequest requestBody = new CreatePipelineRequest(null);

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/pipelines", requestBody)
                            .contentType(MediaType.APPLICATION_JSON),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testCreateDuplicatePipeline() {
        // Create a pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("test-pipeline-duplicate");

        // First, ensure the pipeline doesn't exist by trying to get it
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/test-pipeline-duplicate"),
                    PipelineConfigDto.class);
            // If we get here, the pipeline exists, so delete it
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/test-pipeline-duplicate"));
        } catch (HttpClientResponseException e) {
            // Expected if the pipeline doesn't exist
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        // Create the pipeline
        HttpResponse<PipelineConfigDto> response = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, response.status());

        // Try to create a pipeline with the same name
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/pipelines", requestBody)
                            .contentType(MediaType.APPLICATION_JSON),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());

        // Clean up by deleting the pipeline
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/pipelines/test-pipeline-duplicate"));
    }

    @Test
    void testGetPipeline() {
        // Create a pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("test-pipeline");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        // Get the pipeline
        HttpResponse<PipelineConfigDto> response = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/test-pipeline"),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, response.status());
        assertNotNull(response.body());
        assertEquals("test-pipeline", response.body().getName());
        assertEquals(1, response.body().getPipelineVersion());
    }

    @Test
    void testGetNonExistentPipeline() {
        // Try to get a pipeline that doesn't exist
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/non-existent-pipeline"),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void testDeletePipeline() {
        // Create a pipeline with a unique name for this test
        String pipelineName = "test-pipeline-delete";
        CreatePipelineRequest requestBody = new CreatePipelineRequest(pipelineName);

        // First, ensure the pipeline doesn't exist by trying to get it
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/" + pipelineName),
                    PipelineConfigDto.class);
            // If we get here, the pipeline exists, so delete it
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/" + pipelineName));
        } catch (HttpClientResponseException e) {
            // Expected if the pipeline doesn't exist
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        // Create the pipeline
        HttpResponse<PipelineConfigDto> createResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, createResponse.status());

        // Verify the pipeline was created
        HttpResponse<PipelineConfigDto> getResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines/" + pipelineName),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, getResponse.status());
        assertEquals(pipelineName, getResponse.body().getName());

        // Delete the pipeline
        HttpResponse<?> deleteResponse = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/pipelines/" + pipelineName));

        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.status());

        // Verify the pipeline is deleted
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/" + pipelineName),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void testDeleteNonExistentPipeline() {
        // Try to delete a pipeline that doesn't exist
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/non-existent-pipeline"));
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void testUpdatePipeline() {
        // Use a unique pipeline name for this test
        String pipelineName = "test-pipeline-update";

        // First, ensure the pipeline doesn't exist by trying to get it
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/" + pipelineName),
                    PipelineConfigDto.class);
            // If we get here, the pipeline exists, so delete it
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/" + pipelineName));
        } catch (HttpClientResponseException e) {
            // Expected if the pipeline doesn't exist
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        // Create a pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest(pipelineName);

        HttpResponse<PipelineConfigDto> createResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, createResponse.status());
        PipelineConfigDto pipeline = createResponse.body();
        assertNotNull(pipeline);
        assertEquals(pipelineName, pipeline.getName());
        assertEquals(1, pipeline.getPipelineVersion());

        // Update the pipeline
        HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(
                HttpRequest.PUT("/api/pipelines/" + pipelineName, pipeline)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, updateResponse.status());
        assertNotNull(updateResponse.body());
        assertEquals(pipelineName, updateResponse.body().getName());
        assertEquals(2, updateResponse.body().getPipelineVersion()); // Version should be incremented

        // Clean up by deleting the pipeline
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/pipelines/" + pipelineName));
    }

    @Test
    void testUpdateNonExistentPipeline() {
        // Try to update a pipeline that doesn't exist
        PipelineConfigDto pipeline = new PipelineConfigDto("non-existent-pipeline");

        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.PUT("/api/pipelines/non-existent-pipeline", pipeline)
                            .contentType(MediaType.APPLICATION_JSON),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
    }

    @Test
    void testUpdatePipelineWithVersionConflict() {
        // Create a pipeline
        CreatePipelineRequest requestBody = new CreatePipelineRequest("test-pipeline");

        HttpResponse<PipelineConfigDto> createResponse = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        PipelineConfigDto pipeline = createResponse.body();
        assertNotNull(pipeline);

        // Update the pipeline once
        HttpResponse<PipelineConfigDto> updateResponse = client.toBlocking().exchange(
                HttpRequest.PUT("/api/pipelines/test-pipeline", pipeline)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.OK, updateResponse.status());

        // Try to update with the original pipeline (version conflict)
        HttpClientResponseException exception = assertThrows(HttpClientResponseException.class, () -> {
            client.toBlocking().exchange(
                    HttpRequest.PUT("/api/pipelines/test-pipeline", pipeline)
                            .contentType(MediaType.APPLICATION_JSON),
                    PipelineConfigDto.class);
        });

        assertEquals(HttpStatus.CONFLICT, exception.getStatus());
    }

    @Test
    void testListPipelinesWithMultiplePipelines() {
        // Use unique pipeline names for this test
        String pipeline1Name = "pipeline1-list-test";
        String pipeline2Name = "pipeline2-list-test";

        // First, ensure the pipelines don't exist by trying to get them
        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/" + pipeline1Name),
                    PipelineConfigDto.class);
            // If we get here, the pipeline exists, so delete it
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/" + pipeline1Name));
        } catch (HttpClientResponseException e) {
            // Expected if the pipeline doesn't exist
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        try {
            client.toBlocking().exchange(
                    HttpRequest.GET("/api/pipelines/" + pipeline2Name),
                    PipelineConfigDto.class);
            // If we get here, the pipeline exists, so delete it
            client.toBlocking().exchange(
                    HttpRequest.DELETE("/api/pipelines/" + pipeline2Name));
        } catch (HttpClientResponseException e) {
            // Expected if the pipeline doesn't exist
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }

        // Create the pipelines
        CreatePipelineRequest requestBody1 = new CreatePipelineRequest(pipeline1Name);
        HttpResponse<PipelineConfigDto> response1 = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody1)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, response1.status());

        CreatePipelineRequest requestBody2 = new CreatePipelineRequest(pipeline2Name);
        HttpResponse<PipelineConfigDto> response2 = client.toBlocking().exchange(
                HttpRequest.POST("/api/pipelines", requestBody2)
                        .contentType(MediaType.APPLICATION_JSON),
                PipelineConfigDto.class);

        assertEquals(HttpStatus.CREATED, response2.status());

        // List all pipelines
        HttpResponse<String> listResponse = client.toBlocking().exchange(
                HttpRequest.GET("/api/pipelines"), String.class);

        assertEquals(HttpStatus.OK, listResponse.status());
        assertNotNull(listResponse.body());

        // Parse the JSON array manually
        String body = listResponse.body();
        assertTrue(body.contains(pipeline1Name), "Response should contain " + pipeline1Name + " but was: " + body);
        assertTrue(body.contains(pipeline2Name), "Response should contain " + pipeline2Name + " but was: " + body);

        // Clean up by deleting the pipelines
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/pipelines/" + pipeline1Name));
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/pipelines/" + pipeline2Name));
    }
}
