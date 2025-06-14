package com.krickert.search.pipeline.api.controller;

import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.PipelineService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class PipelineControllerTest {

    @Inject
    @Client("/api/v1/pipelines")
    HttpClient client;

    @Inject
    PipelineService pipelineService;

    @Test
    void testListPipelines() {
        // Given
        var summary1 = new PipelineSummary(
                "pipeline-1", "Pipeline 1", "Test pipeline 1", 3, true,
                List.of("test"), Instant.now()
        );
        var summary2 = new PipelineSummary(
                "pipeline-2", "Pipeline 2", "Test pipeline 2", 2, false,
                List.of("prod"), Instant.now()
        );
        when(pipelineService.listPipelines("default"))
                .thenReturn(Flux.just(summary1, summary2));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/"),
                PipelineSummary[].class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals(2, response.body().length);
        verify(pipelineService).listPipelines("default");
    }

    @Test
    void testGetPipeline() {
        // Given
        var pipelineView = createTestPipelineView();
        when(pipelineService.getPipeline("default", "test-pipeline"))
                .thenReturn(Mono.just(pipelineView));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/test-pipeline"),
                PipelineView.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("test-pipeline", response.body().id());
        verify(pipelineService).getPipeline("default", "test-pipeline");
    }

    @Test
    void testCreatePipeline() {
        // Given
        var createRequest = new CreatePipelineRequest(
                "new-pipeline",
                "New Pipeline",
                "A new test pipeline",
                List.of(
                        new CreatePipelineRequest.StepDefinition(
                                "step1", "tika-parser", Map.of(), List.of("step2")
                        ),
                        new CreatePipelineRequest.StepDefinition(
                                "step2", "chunker", Map.of("size", 512), List.of()
                        )
                ),
                List.of("test"),
                Map.of()
        );
        var pipelineView = createTestPipelineView();
        when(pipelineService.createPipeline(eq("default"), any(CreatePipelineRequest.class)))
                .thenReturn(Mono.just(pipelineView));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/", createRequest),
                PipelineView.class
        );

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatus());
        assertNotNull(response.body());
        verify(pipelineService).createPipeline(eq("default"), any(CreatePipelineRequest.class));
    }

    @Test
    void testCreatePipelineWithInvalidRequest() {
        // Given - missing required fields
        var invalidRequest = new CreatePipelineRequest(
                "", // Invalid empty ID
                "New Pipeline",
                "Description",
                List.of(), // Empty steps
                null,
                null
        );

        // When/Then
        assertThrows(HttpClientResponseException.class, () -> 
                client.toBlocking().exchange(
                        HttpRequest.POST("/", invalidRequest),
                        PipelineView.class
                )
        );
    }

    @Test
    void testUpdatePipeline() {
        // Given
        var updateRequest = new UpdatePipelineRequest(
                "Updated Pipeline",
                "Updated description",
                null, // Don't update steps
                List.of("updated"),
                true,
                Map.of("new-config", "value")
        );
        var updatedView = createTestPipelineView();
        when(pipelineService.updatePipeline(eq("default"), eq("test-pipeline"), any(UpdatePipelineRequest.class)))
                .thenReturn(Mono.just(updatedView));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.PUT("/test-pipeline", updateRequest),
                PipelineView.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        verify(pipelineService).updatePipeline(eq("default"), eq("test-pipeline"), any(UpdatePipelineRequest.class));
    }

    @Test
    void testDeletePipeline() {
        // Given
        when(pipelineService.deletePipeline("default", "test-pipeline"))
                .thenReturn(Mono.empty());

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.DELETE("/test-pipeline")
        );

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        verify(pipelineService).deletePipeline("default", "test-pipeline");
    }

    @Test
    void testTestPipeline() {
        // Given
        var testRequest = new TestPipelineRequest(
                "Test content",
                "text/plain",
                Map.of("source", "test"),
                30
        );
        var testResponse = new TestPipelineResponse(
                true,
                Duration.ofSeconds(2),
                List.of(
                        new TestPipelineResponse.StepResult(
                                "step1", "tika-parser", Duration.ofMillis(500),
                                true, Map.of("text", "extracted"), null
                        )
                ),
                List.of(),
                Map.of("final", "output")
        );
        when(pipelineService.testPipeline(eq("default"), eq("test-pipeline"), any(TestPipelineRequest.class)))
                .thenReturn(Mono.just(testResponse));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/test-pipeline/test", testRequest),
                TestPipelineResponse.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().success());
        assertEquals(1, response.body().stepResults().size());
        verify(pipelineService).testPipeline(eq("default"), eq("test-pipeline"), any(TestPipelineRequest.class));
    }

    @Test
    void testValidatePipeline() {
        // Given
        var createRequest = new CreatePipelineRequest(
                "valid-pipeline",
                "Valid Pipeline",
                "Description",
                List.of(
                        new CreatePipelineRequest.StepDefinition(
                                "step1", "tika-parser", Map.of(), List.of()
                        )
                ),
                List.of(),
                Map.of()
        );
        var validationResponse = new ValidationResponse(
                true,
                List.of(),
                List.of()
        );
        when(pipelineService.validatePipeline(any(CreatePipelineRequest.class)))
                .thenReturn(Mono.just(validationResponse));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/validate", createRequest),
                ValidationResponse.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().valid());
        verify(pipelineService).validatePipeline(any(CreatePipelineRequest.class));
    }

    @Test
    void testGetTemplates() {
        // Given
        var template = new PipelineTemplate(
                "nlp-basic",
                "Basic NLP Pipeline",
                "Standard NLP processing",
                List.of("text-processing"),
                "nlp",
                List.of(),
                List.of(),
                Map.of()
        );
        when(pipelineService.getTemplates())
                .thenReturn(Flux.just(template));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/templates"),
                PipelineTemplate[].class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals(1, response.body().length);
        verify(pipelineService).getTemplates();
    }

    @Test
    void testCustomClusterParameter() {
        // Given
        when(pipelineService.listPipelines("custom-cluster"))
                .thenReturn(Flux.empty());

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/?cluster=custom-cluster"),
                PipelineSummary[].class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        verify(pipelineService).listPipelines("custom-cluster");
    }

    @MockBean(PipelineService.class)
    PipelineService pipelineService() {
        return mock(PipelineService.class);
    }

    private PipelineView createTestPipelineView() {
        return new PipelineView(
                "test-pipeline",
                "Test Pipeline",
                "A test pipeline",
                List.of(
                        new PipelineView.PipelineStepView(
                                "step1", "tika-parser", Map.of(),
                                List.of("step2"), "direct", null
                        ),
                        new PipelineView.PipelineStepView(
                                "step2", "chunker", Map.of("size", 512),
                                List.of(), "direct", null
                        )
                ),
                List.of("test"),
                true,
                Instant.now(),
                Instant.now(),
                "default"
        );
    }
}