package com.krickert.search.pipeline.api.controller;

import com.krickert.search.pipeline.api.dto.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
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
@Property(name = "consul.client.host") // Trigger consul test resource
@Property(name = "consul.client.port")
class TestUtilityControllerIntegrationTest {
    
    @Property(name = "consul.client.host")
    String consulHost;
    
    @Property(name = "consul.client.port")
    Integer consulPort;

    @Inject
    @Client("/api/v1/test-utils")
    HttpClient client;

    @Inject
    Consul consul;

    private KeyValueClient kvClient;

    @BeforeEach
    void setup() {
        kvClient = consul.keyValueClient();
        // Clean up test keys
        cleanupTestKeys();
    }

    @AfterEach
    void cleanup() {
        cleanupTestKeys();
    }

    @Test
    void testSeedKvStore() {
        // Given
        String testKey = "test/integration/seed-test";
        Map<String, Object> testData = Map.of(
            "name", "Test Data",
            "value", 123,
            "nested", Map.of("key", "value")
        );

        // When
        HttpRequest<Map<String, Object>> httpRequest = HttpRequest.POST("/kv/seed?key=" + testKey, testData);
        HttpResponse<Object> response = client.toBlocking().exchange(httpRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify in Consul
        var value = kvClient.getValueAsString(testKey);
        assertTrue(value.isPresent(), "Key should exist in Consul");
        assertTrue(value.get().contains("Test Data"));
        assertTrue(value.get().contains("123"));
    }

    @Test
    void testCleanKvStore() {
        // Given - seed some test data first
        String prefix = "test/integration/clean";
        kvClient.putValue(prefix + "/key1", "value1");
        kvClient.putValue(prefix + "/key2", "value2");
        kvClient.putValue(prefix + "/subfolder/key3", "value3");
        
        // Verify they exist
        assertTrue(kvClient.getValueAsString(prefix + "/key1").isPresent());
        assertTrue(kvClient.getValueAsString(prefix + "/key2").isPresent());
        assertTrue(kvClient.getValueAsString(prefix + "/subfolder/key3").isPresent());

        // When
        HttpRequest<Object> httpRequest = HttpRequest.DELETE("/kv/clean?prefix=" + prefix);
        HttpResponse<Object> response = client.toBlocking().exchange(httpRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify all keys are deleted
        assertFalse(kvClient.getValueAsString(prefix + "/key1").isPresent());
        assertFalse(kvClient.getValueAsString(prefix + "/key2").isPresent());
        assertFalse(kvClient.getValueAsString(prefix + "/subfolder/key3").isPresent());
    }

    @Test
    void testVerifyEnvironment() {
        // When
        HttpRequest<Object> httpRequest = HttpRequest.GET("/environment/verify");
        HttpResponse<EnvironmentStatus> response = client.toBlocking().exchange(httpRequest, EnvironmentStatus.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        EnvironmentStatus status = response.body();
        assertNotNull(status);
        assertNotNull(status.status());
        assertNotNull(status.services());
        assertFalse(status.services().isEmpty());
        
        // Should at least have Consul in the services
        assertTrue(status.services().stream()
            .anyMatch(s -> s.name().toLowerCase().contains("consul")));
    }

    @Test
    void testCheckHealth() {
        // When - check Consul health
        HttpRequest<Object> httpRequest = HttpRequest.GET("/health/consul?host=" + consulHost + "&port=" + consulPort);
        HttpResponse<HealthCheckResponse> response = client.toBlocking().exchange(httpRequest, HealthCheckResponse.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        HealthCheckResponse health = response.body();
        assertNotNull(health);
        assertEquals("consul", health.serviceName());
        assertNotNull(health.status());
        assertNotNull(health.checkedAt());
    }

    @Test
    void testRegisterSchema() {
        // Given
        String schemaId = "test-schema-1";
        String schema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "value": {"type": "number"}
              },
              "required": ["name", "value"]
            }
            """;

        // When
        HttpRequest<String> httpRequest = HttpRequest.POST("/schemas/register?schemaId=" + schemaId, schema);
        HttpResponse<Object> response = client.toBlocking().exchange(httpRequest);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify schema is stored in Consul
        String schemaKey = "config/pipeline/schemas/" + schemaId;
        var storedSchema = kvClient.getValueAsString(schemaKey);
        assertTrue(storedSchema.isPresent(), "Schema should be stored in Consul");
    }

    @Test
    void testValidateAgainstSchema() {
        // Given - register a schema first
        String schemaId = "test-validation-schema";
        String schema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "age": {"type": "integer", "minimum": 0}
              },
              "required": ["name", "age"]
            }
            """;
        
        // Register the schema
        HttpRequest<String> schemaRequest = HttpRequest.POST("/schemas/register?schemaId=" + schemaId, schema);
        client.toBlocking().exchange(schemaRequest);

        // Valid JSON to test
        String validJson = """
            {
              "name": "John Doe",
              "age": 30
            }
            """;

        // When
        HttpRequest<String> httpRequest = HttpRequest.POST("/schemas/validate?schemaId=" + schemaId, validJson);
        HttpResponse<ValidationResult> response = client.toBlocking().exchange(httpRequest, ValidationResult.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        ValidationResult result = response.body();
        assertNotNull(result);
        assertTrue(result.valid());
        assertTrue(result.messages() == null || result.messages().isEmpty());
    }

    @Test
    void testValidateAgainstSchemaWithErrors() {
        // Given - register a schema first
        String schemaId = "test-validation-schema-errors";
        String schema = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "name": {"type": "string"},
                "age": {"type": "integer", "minimum": 0}
              },
              "required": ["name", "age"]
            }
            """;
        
        // Register the schema
        HttpRequest<String> schemaRequest = HttpRequest.POST("/schemas/register?schemaId=" + schemaId, schema);
        client.toBlocking().exchange(schemaRequest);

        // Invalid JSON to test (missing required field)
        String invalidJson = """
            {
              "name": "John Doe"
            }
            """;

        // When
        HttpRequest<String> httpRequest = HttpRequest.POST("/schemas/validate?schemaId=" + schemaId, invalidJson);
        HttpResponse<ValidationResult> response = client.toBlocking().exchange(httpRequest, ValidationResult.class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus()); // Validation endpoint returns 200 even with errors
        ValidationResult result = response.body();
        assertNotNull(result);
        assertFalse(result.valid());
        assertNotNull(result.messages());
        assertFalse(result.messages().isEmpty());
        
        // Should have error about missing 'age' field
        assertTrue(result.messages().stream()
            .anyMatch(m -> m.severity().equals("error") && m.message().contains("age")));
    }

    @Test
    void testGenerateTestDocuments() {
        // Given
        TestDataGenerationRequest request = new TestDataGenerationRequest(
            "text",
            5,
            "small",
            "Test document ${index}",
            Map.of("prefix", "TEST"),
            12345L
        );

        // When
        HttpRequest<TestDataGenerationRequest> httpRequest = HttpRequest.POST("/data/documents", request);
        HttpResponse<TestDocument[]> response = client.toBlocking().exchange(httpRequest, TestDocument[].class);

        // Then
        assertEquals(HttpStatus.OK, response.getStatus());
        TestDocument[] documents = response.body();
        assertNotNull(documents);
        assertEquals(5, documents.length);
        
        for (int i = 0; i < documents.length; i++) {
            TestDocument doc = documents[i];
            assertNotNull(doc.id());
            assertNotNull(doc.content());
            assertEquals("text/plain", doc.contentType());
            assertNotNull(doc.generatedAt());
        }
    }

    private void cleanupTestKeys() {
        try {
            // Clean up any test keys
            var testPrefixes = List.of(
                "test/integration/",
                "config/pipeline/schemas/test-"
            );
            
            for (String prefix : testPrefixes) {
                var keys = kvClient.getKeys(prefix);
                keys.forEach(key -> {
                    try {
                        kvClient.deleteKey(key);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                });
            }
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}