package com.krickert.search.pipeline.api.controller;

import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.pipeline.api.service.ModuleService;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@MicronautTest
class ModuleControllerTest {

    @Inject
    @Client("/api/v1/modules")
    HttpClient client;

    @Inject
    ModuleService moduleService;

    @Test
    void testListModules() {
        // Given
        var module1 = createTestModuleInfo("tika-parser", "Apache Tika Parser");
        var module2 = createTestModuleInfo("chunker", "Text Chunker");
        when(moduleService.listModules("default"))
                .thenReturn(Flux.just(module1, module2));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/"),
                Argument.listOf(ModuleInfo.class)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals(2, response.body().size());
        verify(moduleService).listModules("default");
    }

    @Test
    void testGetModule() {
        // Given
        var moduleInfo = createTestModuleInfo("tika-parser", "Apache Tika Parser");
        when(moduleService.getModule("default", "tika-parser"))
                .thenReturn(Mono.just(moduleInfo));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/tika-parser"),
                ModuleInfo.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("tika-parser", response.body().serviceId());
        verify(moduleService).getModule("default", "tika-parser");
    }

    @Test
    void testRegisterModule() {
        // Given
        var request = new ModuleRegistrationRequest(
                "new-module",
                "New Module",
                "A new processing module",
                "1.0.0",
                "new-module.local",
                50051,
                List.of("processing"),
                List.of("text/plain"),
                List.of("application/json"),
                Map.of(),
                "/health",
                Map.of()
        );
        var response = new ModuleRegistrationResponse(
                "new-module",
                "registered",
                Instant.now(),
                "new-module-consul-id",
                "Module registered successfully"
        );
        when(moduleService.registerModule(eq("default"), any(ModuleRegistrationRequest.class)))
                .thenReturn(Mono.just(response));

        // When
        var httpResponse = client.toBlocking().exchange(
                HttpRequest.POST("/register", request),
                ModuleRegistrationResponse.class
        );

        // Then
        assertEquals(HttpStatus.CREATED, httpResponse.getStatus());
        assertNotNull(httpResponse.body());
        assertEquals("registered", httpResponse.body().status());
        verify(moduleService).registerModule(eq("default"), any(ModuleRegistrationRequest.class));
    }

    @Test
    void testUpdateModule() {
        // Given
        var updateRequest = new ModuleUpdateRequest(
                "Updated Module Name",
                "Updated description",
                "1.0.1",
                null, // Don't update host
                null, // Don't update port
                List.of("new-capability"),
                null,
                null,
                null,
                Map.of("updated", "true")
        );
        var updatedModule = createTestModuleInfo("test-module", "Updated Module Name");
        when(moduleService.updateModule(eq("default"), eq("test-module"), any(ModuleUpdateRequest.class)))
                .thenReturn(Mono.just(updatedModule));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.PUT("/test-module", updateRequest),
                ModuleInfo.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        verify(moduleService).updateModule(eq("default"), eq("test-module"), any(ModuleUpdateRequest.class));
    }

    @Test
    void testDeregisterModule() {
        // Given
        when(moduleService.deregisterModule("default", "test-module"))
                .thenReturn(Mono.empty());

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.DELETE("/test-module")
        );

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        verify(moduleService).deregisterModule("default", "test-module");
    }

    @Test
    void testCheckModuleHealth() {
        // Given
        var healthStatus = new ModuleHealthStatus(
                "test-module",
                "healthy",
                Instant.now(),
                150L,
                List.of(
                        new ModuleHealthStatus.InstanceHealth(
                                "instance-1",
                                "10.0.0.1:50051",
                                "healthy",
                                Instant.now(),
                                Map.of()
                        )
                ),
                Map.of("version", "1.0.0"),
                null
        );
        when(moduleService.checkHealth("default", "test-module"))
                .thenReturn(Mono.just(healthStatus));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/test-module/health"),
                ModuleHealthStatus.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals("healthy", response.body().status());
        assertEquals(1, response.body().instances().size());
        verify(moduleService).checkHealth("default", "test-module");
    }

    @Test
    void testTestModule() {
        // Given
        var testRequest = new ModuleTestRequest(
                "Test content to process",
                "text/plain",
                Map.of("testParam", "value"),
                Map.of("source", "test"),
                10
        );
        var testResponse = new ModuleTestResponse(
                true,
                Duration.ofMillis(250),
                Map.of("processed", "result"),
                "application/json",
                "test-module",
                "instance-1",
                null,
                Map.of("processingTime", "250ms")
        );
        when(moduleService.testModule(eq("default"), eq("test-module"), any(ModuleTestRequest.class)))
                .thenReturn(Mono.just(testResponse));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.POST("/test-module/test", testRequest),
                ModuleTestResponse.class
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertTrue(response.body().success());
        assertEquals("test-module", response.body().moduleId());
        verify(moduleService).testModule(eq("default"), eq("test-module"), any(ModuleTestRequest.class));
    }

    @Test
    void testGetModuleTemplates() {
        // Given
        var template = new ModuleTemplate(
                "tika-basic",
                "tika-parser",
                "Basic Tika Configuration",
                "Standard configuration for Apache Tika",
                List.of("Document parsing", "Text extraction"),
                Map.of("maxFileSize", "10MB", "parseTimeout", 30000),
                List.of(
                        new ModuleTemplate.ConfigParameter(
                                "maxFileSize",
                                "Maximum file size to process",
                                "string",
                                "10MB",
                                true,
                                Map.of("pattern", "^\\d+[KMG]B$")
                        )
                )
        );
        when(moduleService.getTemplates())
                .thenReturn(Flux.just(template));

        // When
        var response = client.toBlocking().exchange(
                HttpRequest.GET("/templates"),
                Argument.listOf(ModuleTemplate.class)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        assertNotNull(response.body());
        assertEquals(1, response.body().size());
        verify(moduleService).getTemplates();
    }

    @Test
    void testValidationForInvalidModuleRegistration() {
        // Given - invalid service ID pattern
        var invalidRequest = new ModuleRegistrationRequest(
                "Invalid-Module-ID", // Should be lowercase
                "Module Name",
                "Description",
                "1.0.0",
                "host",
                50051,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                "/health",
                Map.of()
        );

        // When/Then - expect validation error
        var exception = assertThrows(
                io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/register", invalidRequest),
                        String.class
                )
        );
        
        // Should get a 400 Bad Request due to validation
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("Service ID must be lowercase letters, numbers, and hyphens only"));
    }

    @MockBean(ModuleService.class)
    ModuleService moduleService() {
        return mock(ModuleService.class);
    }

    private ModuleInfo createTestModuleInfo(String serviceId, String name) {
        return new ModuleInfo(
                serviceId,
                name,
                "Test module description",
                "1.0.0",
                2,
                "healthy",
                List.of("test-capability"),
                List.of("text/plain"),
                List.of("application/json"),
                Map.of("param1", Map.of("type", "string")),
                Instant.now(),
                Instant.now()
        );
    }
}