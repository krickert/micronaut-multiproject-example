package com.krickert.search.pipeline.api.controller;

import com.krickert.search.pipeline.api.dto.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.*;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "consul.client.host", value = "localhost") // Trigger consul test resource
@Property(name = "consul.client.port", value = "8500")
class PipelineControllerIntegrationTest {

    @Inject
    @Client("/api/v1/pipelines")
    HttpClient client;

    @Inject
    Consul consul;

    private KeyValueClient kvClient;

    @BeforeEach
    void setup() {
        kvClient = consul.keyValueClient();
        // Clean up any existing test pipelines
        cleanupTestPipelines();
    }

    @AfterEach
    void cleanup() {
        cleanupTestPipelines();
    }

    @Test
    @Order(1)
    void testCreatePipeline() {
        // Given
        CreatePipelineRequest request = new CreatePipelineRequest(
            "test-pipeline-1",
            "Test Pipeline 1",
            "Integration test pipeline",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "parse-step",
                    "tika-parser",
                    Map.of("maxFileSize", "10MB"),
                    List.of("chunk-step")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "chunk-step",
                    "chunker",
                    Map.of("chunkSize", 500),
                    List.of("embed-step")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "embed-step",
                    "embedder",
                    Map.of("model", "text-embedding-ada-002"),
                    null
                )
            ),
            List.of("test", "integration"),
            Map.of("environment", "test")
        );

        // When
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("test-pipeline-1", pipeline.id());
        assertEquals("Test Pipeline 1", pipeline.name());
        assertEquals(3, pipeline.steps().size());

        // Verify in Consul KV
        String pipelineKey = "clusters/default/pipelines/test-pipeline-1";
        var kvValue = kvClient.getValueAsString(pipelineKey);
        assertTrue(kvValue.isPresent(), "Pipeline should be stored in Consul KV");
        assertTrue(kvValue.get().contains("test-pipeline-1"));
    }

    @Test
    @Order(2)
    void testListPipelines() {
        // Given - create test pipelines
        createTestPipeline("test-list-1", "Test List Pipeline 1");
        createTestPipeline("test-list-2", "Test List Pipeline 2");

        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/");
        HttpResponse<List<PipelineSummary>> response = client.toBlocking().exchange(
            httpRequest,
            Argument.listOf(PipelineSummary.class)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        List<PipelineSummary> pipelines = response.body();
        assertNotNull(pipelines);
        assertTrue(pipelines.size() >= 2, "Should have at least 2 pipelines");
        
        // Verify our test pipelines are in the list
        assertTrue(pipelines.stream().anyMatch(p -> p.id().equals("test-list-1")));
        assertTrue(pipelines.stream().anyMatch(p -> p.id().equals("test-list-2")));
    }

    @Test
    @Order(3)
    void testGetPipeline() {
        // Given - create a pipeline first
        createTestPipeline("test-get-pipeline", "Test Get Pipeline");

        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/test-get-pipeline");
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("test-get-pipeline", pipeline.id());
        assertEquals("Test Get Pipeline", pipeline.name());
        assertNotNull(pipeline.steps());
    }

    @Test
    @Order(4)
    void testUpdatePipeline() {
        // Given - create a pipeline first
        createTestPipeline("test-update-pipeline", "Original Name");

        UpdatePipelineRequest updateRequest = new UpdatePipelineRequest(
            "Updated Pipeline Name",
            "Updated description",
            null, // Not updating steps
            List.of("updated", "test"),
            true,
            Map.of("updated", "true")
        );

        // When
        HttpRequest<UpdatePipelineRequest> httpRequest = HttpRequest.PUT("/test-update-pipeline", updateRequest);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        PipelineView updated = response.body();
        assertNotNull(updated);
        assertEquals("test-update-pipeline", updated.id());
        assertEquals("Updated Pipeline Name", updated.name());
        assertEquals("Updated description", updated.description());
        assertTrue(updated.tags().contains("updated"));
    }

    @Test
    @Order(5)
    void testDeletePipeline() {
        // Given - create a pipeline first
        createTestPipeline("test-delete-pipeline", "Test Delete Pipeline");
        
        // Verify it exists
        String pipelineKey = "clusters/default/pipelines/test-delete-pipeline";
        assertTrue(kvClient.getValueAsString(pipelineKey).isPresent());

        // When
        HttpRequest<Object> httpRequest = HttpRequest.DELETE("/test-delete-pipeline");
        HttpResponse<Void> response = client.toBlocking().exchange(httpRequest, Void.class);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        
        // Verify it's gone from Consul
        assertFalse(kvClient.getValueAsString(pipelineKey).isPresent(), 
            "Pipeline should be deleted from Consul KV");
    }

    @Test
    void testValidatePipeline() {
        // Given - valid pipeline config
        CreatePipelineRequest request = new CreatePipelineRequest(
            "test-validate",
            "Test Validate",
            "Test validation",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "step1",
                    "tika-parser",
                    null,
                    List.of("step2")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "step2",
                    "chunker",
                    null,
                    null
                )
            ),
            null,
            null
        );

        // When
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/validate", request);
        HttpResponse<ValidationResponse> response = client.toBlocking().exchange(httpRequest, ValidationResponse.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        ValidationResponse validation = response.body();
        assertNotNull(validation);
        assertTrue(validation.valid());
        assertTrue(validation.errors() == null || validation.errors().isEmpty());
    }

    @Test
    void testValidatePipelineWithErrors() {
        // Given - invalid pipeline config (circular reference)
        CreatePipelineRequest request = new CreatePipelineRequest(
            "test-validate-error",
            "Test Validate Error",
            "Test validation with errors",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "step1",
                    "tika-parser",
                    null,
                    List.of("step2")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "step2",
                    "chunker",
                    null,
                    List.of("step1") // Circular reference
                )
            ),
            null,
            null
        );

        // When
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/validate", request);
        HttpResponse<ValidationResponse> response = client.toBlocking().exchange(httpRequest, ValidationResponse.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus()); // Validation endpoint returns 200 even with errors
        ValidationResponse validation = response.body();
        assertNotNull(validation);
        assertFalse(validation.valid());
        assertNotNull(validation.errors());
        assertFalse(validation.errors().isEmpty());
    }

    @Test
    void testCreatePipelineWithInvalidId() {
        // Given - invalid pipeline ID with uppercase
        CreatePipelineRequest request = new CreatePipelineRequest(
            "Test-Pipeline-Invalid", // Invalid: has uppercase
            "Test Pipeline",
            "Test pipeline",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "step1",
                    "module1",
                    null,
                    null
                )
            ),
            null,
            null
        );

        // When/Then
        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/", request);
        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange(httpRequest, PipelineView.class)
        );
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("ID must be lowercase"));
    }

    @Test
    void testTestPipeline() {
        // Given - create a pipeline first
        createTestPipeline("test-pipeline-test", "Test Pipeline Test");
        
        TestPipelineRequest request = new TestPipelineRequest(
            "This is test content to process through the pipeline",
            "text/plain",
            Map.of("source", "integration-test"),
            30
        );

        // When
        HttpRequest<TestPipelineRequest> httpRequest = HttpRequest.POST("/test-pipeline-test/test", request);
        HttpResponse<TestPipelineResponse> response = client.toBlocking().exchange(httpRequest, TestPipelineResponse.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        TestPipelineResponse testResponse = response.body();
        assertNotNull(testResponse);
        // The test implementation returns success=true by default
        assertTrue(testResponse.success());
    }

    private void createTestPipeline(String id, String name) {
        CreatePipelineRequest request = new CreatePipelineRequest(
            id,
            name,
            "Test pipeline for integration testing",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "step1",
                    "test-module",
                    null,
                    null
                )
            ),
            List.of("test"),
            null
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/", request);
        client.toBlocking().exchange(httpRequest, PipelineView.class);
    }

    private void cleanupTestPipelines() {
        try {
            // List all keys under the pipelines prefix
            String prefix = "clusters/default/pipelines/";
            var keys = kvClient.getKeys(prefix);
            keys.forEach(key -> {
                if (key.contains("test-")) {
                    try {
                        kvClient.deleteKey(key);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }
            });
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}