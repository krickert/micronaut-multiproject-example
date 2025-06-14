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
@Property(name = "consul.client.port")
class ModuleControllerIntegrationTest {

    @Inject
    @Client("/api/v1/modules")
    HttpClient client;

    @Inject
    Consul consul;

    private KeyValueClient kvClient;

    @BeforeEach
    void setup() {
        kvClient = consul.keyValueClient();
        // Clean up any existing test modules
        cleanupTestModules();
    }

    @AfterEach
    void cleanup() {
        cleanupTestModules();
    }

    @Test
    @Order(1)
    void testRegisterModule() {
        // Given
        ModuleRegistrationRequest request = new ModuleRegistrationRequest(
            "test-tika-parser",
            "Test Tika Parser",
            "Test module for parsing documents",
            "1.0.0",
            "localhost",
            50051,
            List.of("parsing", "text-extraction"),
            List.of("application/pdf", "text/plain"),
            List.of("text/plain"),
            Map.of("maxFileSize", Map.of("type", "integer", "default", 10485760)),
            "/health",
            Map.of("environment", "test")
        );

        // When
        HttpRequest<ModuleRegistrationRequest> httpRequest = HttpRequest.POST("/register", request);
        HttpResponse<ModuleRegistrationResponse> response = client.toBlocking().exchange(httpRequest, ModuleRegistrationResponse.class);

        // Then
        assertEquals(HttpStatus.CREATED, response.getStatus());
        ModuleRegistrationResponse body = response.body();
        assertNotNull(body);
        assertEquals("test-tika-parser", body.serviceId());
        assertEquals("registered", body.status());
        assertNotNull(body.registeredAt());
        assertNotNull(body.consulServiceId());

        // Verify in Consul
        var agentClient = consul.agentClient();
        var services = agentClient.getServices();
        assertTrue(services.containsKey("test-tika-parser"), "Module should be registered in Consul");
        
        var service = services.get("test-tika-parser");
        assertEquals("Test Tika Parser", service.getService());
        assertEquals(50051, service.getPort());
        assertTrue(service.getTags().contains("yappy-module"));
        assertTrue(service.getTags().contains("cluster:default"));
        assertTrue(service.getTags().contains("version:1.0.0"));
    }

    @Test
    @Order(2)
    void testListModules() {
        // Given - register a module first
        registerTestModule("test-module-1", "Test Module 1", 50051);
        registerTestModule("test-module-2", "Test Module 2", 50052);

        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/");
        HttpResponse<List<ModuleInfo>> response = client.toBlocking().exchange(
            httpRequest, 
            Argument.listOf(ModuleInfo.class)
        );

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        List<ModuleInfo> modules = response.body();
        assertNotNull(modules);
        assertTrue(modules.size() >= 2, "Should have at least 2 modules");
        
        // Verify our test modules are in the list
        assertTrue(modules.stream().anyMatch(m -> m.serviceId().equals("test-module-1")));
        assertTrue(modules.stream().anyMatch(m -> m.serviceId().equals("test-module-2")));
    }

    @Test
    @Order(3)
    void testGetModule() {
        // Given - register a module first
        registerTestModule("test-get-module", "Test Get Module", 50053);

        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/test-get-module");
        HttpResponse<ModuleInfo> response = client.toBlocking().exchange(httpRequest, ModuleInfo.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        ModuleInfo module = response.body();
        assertNotNull(module);
        assertEquals("test-get-module", module.serviceId());
        assertEquals("Test Get Module", module.name());
        assertEquals("1.0.0", module.version());
    }

    @Test
    @Order(4)
    void testDeregisterModule() {
        // Given - register a module first
        registerTestModule("test-deregister", "Test Deregister", 50054);
        
        // Verify it exists
        var services = consul.agentClient().getServices();
        assertTrue(services.containsKey("test-deregister"));

        // When
        HttpRequest<Object> httpRequest = HttpRequest.DELETE("/test-deregister");
        HttpResponse<Void> response = client.toBlocking().exchange(httpRequest, Void.class);

        // Then
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        
        // Verify it's gone from Consul
        services = consul.agentClient().getServices();
        assertFalse(services.containsKey("test-deregister"), "Module should be deregistered from Consul");
    }

    @Test
    void testRegisterModuleWithInvalidServiceId() {
        // Given - invalid service ID with uppercase
        ModuleRegistrationRequest request = new ModuleRegistrationRequest(
            "Test-Module-Invalid", // Invalid: has uppercase
            "Test Module",
            "Test module",
            "1.0.0",
            "localhost",
            50051,
            null,
            null,
            null,
            null,
            null,
            null
        );

        // When/Then
        HttpRequest<ModuleRegistrationRequest> httpRequest = HttpRequest.POST("/register", request);
        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange(httpRequest, ModuleRegistrationResponse.class)
        );
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
        assertTrue(exception.getMessage().contains("Service ID must be lowercase"));
    }

    @Test
    void testRegisterModuleWithInvalidPort() {
        // Given - invalid port
        ModuleRegistrationRequest request = new ModuleRegistrationRequest(
            "test-module-invalid-port",
            "Test Module",
            "Test module",
            "1.0.0",
            "localhost",
            70000, // Invalid: > 65535
            null,
            null,
            null,
            null,
            null,
            null
        );

        // When/Then
        HttpRequest<ModuleRegistrationRequest> httpRequest = HttpRequest.POST("/register", request);
        HttpClientResponseException exception = assertThrows(
            HttpClientResponseException.class,
            () -> client.toBlocking().exchange(httpRequest, ModuleRegistrationResponse.class)
        );
        
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    @Test
    void testCheckModuleHealth() {
        // Given - register a module first
        registerTestModule("test-health-check", "Test Health Check", 50055);

        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/test-health-check/health");
        HttpResponse<ModuleHealthStatus> response = client.toBlocking().exchange(httpRequest, ModuleHealthStatus.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        ModuleHealthStatus health = response.body();
        assertNotNull(health);
        assertEquals("test-health-check", health.serviceId());
        assertNotNull(health.status());
        assertNotNull(health.checkedAt());
    }

    @Test
    void testTestModule() {
        // Given - register a module first
        registerTestModule("test-module-test", "Test Module Test", 50056);
        
        ModuleTestRequest request = new ModuleTestRequest(
            "Sample test content",
            "text/plain",
            Map.of("test", "true"),
            Map.of("source", "integration-test"),
            30
        );

        // When
        HttpRequest<ModuleTestRequest> httpRequest = HttpRequest.POST("/test-module-test/test", request);
        HttpResponse<ModuleTestResponse> response = client.toBlocking().exchange(httpRequest, ModuleTestResponse.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        ModuleTestResponse testResponse = response.body();
        assertNotNull(testResponse);
        assertTrue(testResponse.success());
        assertEquals("test-module-test", testResponse.moduleId());
    }

    private void registerTestModule(String serviceId, String name, int port) {
        ModuleRegistrationRequest request = new ModuleRegistrationRequest(
            serviceId,
            name,
            "Test module for integration testing",
            "1.0.0",
            "localhost",
            port,
            List.of("test"),
            List.of("text/plain"),
            List.of("text/plain"),
            null,
            null,
            Map.of("test", "true")
        );

        HttpRequest<ModuleRegistrationRequest> httpRequest = HttpRequest.POST("/register", request);
        client.toBlocking().exchange(httpRequest, ModuleRegistrationResponse.class);
    }

    private void cleanupTestModules() {
        try {
            var services = consul.agentClient().getServices();
            services.forEach((id, service) -> {
                if (id.startsWith("test-") && service.getTags().contains("yappy-module")) {
                    try {
                        consul.agentClient().deregister(id);
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