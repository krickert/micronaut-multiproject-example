package com.krickert.search.pipeline.api.controller;

import com.krickert.search.config.pipeline.model.PipelineConfig;
import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.TestUtilityService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class TestUtilityControllerTest {

    @Inject
    @Client("/api/v1/test-utils")
    HttpClient client;

    @Inject
    TestUtilityService testUtilityService;

    @Test
    void testRegisterTestModule() {
        // Given
        var request = new ModuleRegistrationRequest(
                "test-module",
                "Test Module",
                "A test module",
                "1.0.0",
                "localhost",
                50051,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                "/health",
                Map.of()
        );
        var response = new ModuleRegistrationResponse(
                "test-module",
                "registered",
                Instant.now(),
                "consul-id",
                "Module registered"
        );
        when(testUtilityService.registerModule(any(ModuleRegistrationRequest.class)))
                .thenReturn(Mono.just(response));

        // When
        var httpResponse = client.toBlocking().exchange(
                HttpRequest.POST("/modules/register", request),
                ModuleRegistrationResponse.class
        );

        // Then
        assertEquals(HttpStatus.CREATED, httpResponse.getStatus());
        assertNotNull(httpResponse.body());
        verify(testUtilityService).registerModule(any(ModuleRegistrationRequest.class));
    }

    @Test
    void testCreateSimplePipeline() {
        // Given
        var steps = List.of("extract", "chunk", "embed");
        var pipelineConfig = PipelineConfig.builder()
                .name("simple-pipeline")
                .pipelineSteps(Map.of())
                .build();
        when(testUtilityService.createSimplePipeline("simple-pipeline", steps))
                .thenReturn(Mono.just(pipelineConfig));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/pipelines/simple?name=simple-pipeline", steps),
                PipelineConfig.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("simple-pipeline", response.body().name());
        verify(testUtilityService).createSimplePipeline("simple-pipeline", steps);
    }

    @Test
    void testGenerateTestDocuments() {
        // Given
        var request = new TestDataGenerationRequest(
                "pdf",
                5,
                "medium",
                "Test content template",
                Map.of(),
                12345L
        );
        var testDoc = new TestDocument(
                "doc-1",
                "Test Document",
                "Test content",
                "application/pdf",
                1024L,
                Instant.now(),
                Map.of()
        );
        when(testUtilityService.generateTestDocuments(any(TestDataGenerationRequest.class)))
                .thenReturn(Flux.just(testDoc));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/data/documents", request),
                Argument.listOf(TestDocument.class)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals(1, response.body().size());
        verify(testUtilityService).generateTestDocuments(any(TestDataGenerationRequest.class));
    }

    @Test
    void testCheckServiceHealth() {
        // Given
        var healthResponse = new HealthCheckResponse(
                "test-service",
                "healthy",
                Instant.now(),
                java.time.Duration.ofMillis(100),
                "1.0.0",
                Map.of(),
                null
        );
        when(testUtilityService.checkServiceHealth("test-service", "localhost", 50051))
                .thenReturn(Mono.just(healthResponse));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/health/test-service?host=localhost&port=50051"),
                HealthCheckResponse.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("healthy", response.body().status());
        verify(testUtilityService).checkServiceHealth("test-service", "localhost", 50051);
    }

    @Test
    void testVerifyEnvironment() {
        // Given
        var envStatus = new EnvironmentStatus(
                "ready",
                Instant.now(),
                List.of(
                        new EnvironmentStatus.ServiceStatus(
                                "consul", "infrastructure", "running",
                                "1.16.0", "http://consul:8500", true
                        )
                ),
                List.of(),
                true
        );
        when(testUtilityService.verifyEnvironment())
                .thenReturn(Mono.just(envStatus));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/environment/verify"),
                EnvironmentStatus.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().readyForTesting());
        verify(testUtilityService).verifyEnvironment();
    }

    @Test
    void testSeedKvStore() {
        // Given
        var value = Map.of("key", "value", "nested", Map.of("data", "test"));
        when(testUtilityService.seedKvStore("test/key", value))
                .thenReturn(Mono.empty());

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/kv/seed?key=test/key", value)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        verify(testUtilityService).seedKvStore(eq("test/key"), any());
    }

    @Test
    void testValidateAgainstSchema() {
        // Given
        var jsonContent = "{\"name\": \"test\", \"value\": 123}";
        var validationResult = new ValidationResult(
                true,
                List.of(),
                Map.of("schemaId", "test-schema"),
                "json-schema"
        );
        when(testUtilityService.validateAgainstSchema("test-schema", jsonContent))
                .thenReturn(Mono.just(validationResult));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/schemas/validate?schemaId=test-schema", jsonContent),
                ValidationResult.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().valid());
        verify(testUtilityService).validateAgainstSchema("test-schema", jsonContent);
    }

    @Test
    void testCleanKvStore() {
        // Given
        when(testUtilityService.cleanKvStore("test/"))
                .thenReturn(Mono.empty());

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.DELETE("/kv/clean?prefix=test/")
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        verify(testUtilityService).cleanKvStore("test/");
    }

    @MockBean(TestUtilityService.class)
    TestUtilityService testUtilityService() {
        return mock(TestUtilityService.class);
    }
}