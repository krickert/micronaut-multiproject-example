package com.krickert.search.config.consul.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.consul.service.ConsulKvService;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import java.io.IOException;
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
import jakarta.inject.Singleton;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.KeyValueClient;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(rebuildContext = true)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ConfigControllerTest implements TestPropertyProvider {

    @Factory
    static class TestBeanFactory {
        @Bean
        @Singleton
        @jakarta.inject.Named("configControllerTest")
        public Consul consulClient() {
            // Ensure the container is started before creating the client
            if (!consulContainer.isRunning()) {
                consulContainer.start();
            }
            return Consul.builder()
                    .withUrl("http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(8500))
                    .build();
        }
    }

    @Container
    public static ConsulContainer consulContainer = new ConsulContainer("hashicorp/consul:latest")
            .withExposedPorts(8500);
    static {
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
    }

    @Inject
    private ConsulKvService consulKvService;

    @Inject
    @Client("/")
    private HttpClient client;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new HashMap<>();

        // Ensure the container is started before getting host and port
        if (!consulContainer.isRunning()) {
            consulContainer.start();
        }
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", consulContainer.getMappedPort(8500).toString());

        properties.put("consul.client.host", consulContainer.getHost());
        properties.put("consul.client.port", consulContainer.getMappedPort(8500).toString());
        properties.put("consul.client.config.path", "config/test");

        // Disable the Consul config client to prevent Micronaut from trying to connect to Consul for configuration
        properties.put("micronaut.config-client.enabled", "false");

        // Enable data seeding for tests with a custom seed file
        properties.put("consul.data.seeding.enabled", "true");
        properties.put("consul.data.seeding.file", "test-seed-data.yaml");
        properties.put("consul.data.seeding.skip-if-exists", "false");

        return properties;
    }

    @BeforeEach
    public void setUp() {
        // Clean up any existing test keys before each test
        String testKeyPrefix = consulKvService.getFullPath("test-");
        try {
            consulContainer.execInContainer("consul", "kv", "delete", "-recurse", testKeyPrefix);
        } catch (Exception e) {
            // If interrupted, restore the interrupt status
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // Log the error but continue with the test
            System.err.println("Error cleaning up test keys: " + e.getMessage());
        }
    }

    @Test
    public void testCreateAndGetConfig() {
        // Given
        String keyPath = "test-create-key";
        String value = "test-value";

        // When - Create
        HttpRequest<?> createRequest = HttpRequest.PUT("/config/" + keyPath, value)
                .contentType(MediaType.TEXT_PLAIN);
        HttpResponse<?> createResponse = client.toBlocking().exchange(createRequest);

        // Then - Create
        assertEquals(HttpStatus.OK, createResponse.status());

        // When - Get
        HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath);
        HttpResponse<String> getResponse = client.toBlocking().exchange(getRequest, String.class);

        // Then - Get
        assertEquals(HttpStatus.OK, getResponse.status());
        assertEquals(value, getResponse.body());
    }

    @Test
    public void testCreateAndGetConfigWithJson() {
        // Given
        String keyPath = "test-create-json-key";
        Map<String, Object> jsonValue = new HashMap<>();
        jsonValue.put("name", "test-service");
        jsonValue.put("enabled", true);
        jsonValue.put("port", 8080);

        // When - Create
        HttpRequest<?> createRequest = HttpRequest.PUT("/config/" + keyPath, jsonValue)
                .contentType(MediaType.APPLICATION_JSON);
        HttpResponse<?> createResponse = client.toBlocking().exchange(createRequest);

        // Then - Create
        assertEquals(HttpStatus.OK, createResponse.status());

        // When - Get
        HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath)
                .accept(MediaType.APPLICATION_JSON);
        HttpResponse<String> getResponse = client.toBlocking().exchange(getRequest, String.class);

        // Then - Get
        assertEquals(HttpStatus.OK, getResponse.status());

        // Verify the response body contains the expected JSON
        String responseBody = getResponse.body();
        assertNotNull(responseBody, "Response body should not be null");

        try {
            // Parse the response body as JSON
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            // Verify the JSON contains the expected values
            assertEquals("test-service", jsonNode.get("name").asText(), "Name should match");
            assertTrue(jsonNode.get("enabled").asBoolean(), "Enabled should be true");
            assertEquals(8080, jsonNode.get("port").asInt(), "Port should match");
        } catch (IOException e) {
            fail("Failed to parse response body as JSON: " + e.getMessage());
        }
    }

    @Test
    public void testUpdateConfig() {
        // Given
        String keyPath = "test-update-key";
        String initialValue = "initial-value";
        String updatedValue = "updated-value";

        // Create initial value
        HttpRequest<?> createRequest = HttpRequest.PUT("/config/" + keyPath, initialValue)
                .contentType(MediaType.TEXT_PLAIN);
        client.toBlocking().exchange(createRequest);

        // When - Update
        HttpRequest<?> updateRequest = HttpRequest.PUT("/config/" + keyPath, updatedValue)
                .contentType(MediaType.TEXT_PLAIN);
        HttpResponse<?> updateResponse = client.toBlocking().exchange(updateRequest);

        // Then - Update
        assertEquals(HttpStatus.OK, updateResponse.status());

        // Verify updated value
        HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath);
        HttpResponse<String> getResponse = client.toBlocking().exchange(getRequest, String.class);
        assertEquals(updatedValue, getResponse.body());
    }

    @Test
    public void testDeleteConfig() {
        // Given
        String keyPath = "test-delete-key";
        String value = "test-value";

        // Create value
        HttpRequest<?> createRequest = HttpRequest.PUT("/config/" + keyPath, value)
                .contentType(MediaType.TEXT_PLAIN);
        client.toBlocking().exchange(createRequest);

        // When - Delete
        HttpRequest<?> deleteRequest = HttpRequest.DELETE("/config/" + keyPath);
        HttpResponse<?> deleteResponse = client.toBlocking().exchange(deleteRequest);

        // Then - Delete
        assertEquals(HttpStatus.NO_CONTENT, deleteResponse.status());

        // Verify key is deleted
        try {
            HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath);
            client.toBlocking().exchange(getRequest, String.class);
            fail("Expected HttpClientResponseException");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }

    @Test
    public void testGetNonExistentConfig() {
        // Given
        String keyPath = "test-non-existent-key";

        // When & Then
        try {
            HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath);
            client.toBlocking().exchange(getRequest, String.class);
            fail("Expected HttpClientResponseException");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        }
    }

    @Test
    public void testRefresh() {
        // When
        HttpRequest<?> refreshRequest = HttpRequest.POST("/config/refresh", "");
        HttpResponse<?> refreshResponse = client.toBlocking().exchange(refreshRequest);

        // Then
        assertEquals(HttpStatus.OK, refreshResponse.status());
    }

    @Test
    public void testCreateAndUpdateServiceNode() {
        // Given
        String keyPath = "test-pipeline.configs.pipeline1.service.test-service";
        Map<String, Object> initialService = new HashMap<>();
        initialService.put("name", "test-service");
        initialService.put("enabled", true);

        Map<String, Object> updatedService = new HashMap<>();
        updatedService.put("name", "test-service");
        updatedService.put("enabled", false);
        updatedService.put("port", 9090);

        // When - Create
        HttpRequest<?> createRequest = HttpRequest.PUT("/config/" + keyPath, initialService)
                .contentType(MediaType.APPLICATION_JSON);
        HttpResponse<?> createResponse = client.toBlocking().exchange(createRequest);

        // Then - Create
        assertEquals(HttpStatus.OK, createResponse.status());

        // When - Update
        HttpRequest<?> updateRequest = HttpRequest.PUT("/config/" + keyPath, updatedService)
                .contentType(MediaType.APPLICATION_JSON);
        HttpResponse<?> updateResponse = client.toBlocking().exchange(updateRequest);

        // Then - Update
        assertEquals(HttpStatus.OK, updateResponse.status());

        // Verify updated value
        HttpRequest<?> getRequest = HttpRequest.GET("/config/" + keyPath)
                .accept(MediaType.APPLICATION_JSON);
        HttpResponse<String> getResponse = client.toBlocking().exchange(getRequest, String.class);
        assertEquals(HttpStatus.OK, getResponse.status());

        // Verify the response body contains the expected JSON
        String responseBody = getResponse.body();
        assertNotNull(responseBody, "Response body should not be null");

        try {
            // Parse the response body as JSON
            JsonNode jsonNode = objectMapper.readTree(responseBody);

            // Verify the JSON contains the updated values
            assertEquals("test-service", jsonNode.get("name").asText(), "Name should match");
            assertFalse(jsonNode.get("enabled").asBoolean(), "Enabled should be false");
            assertEquals(9090, jsonNode.get("port").asInt(), "Port should match");
        } catch (IOException e) {
            fail("Failed to parse response body as JSON: " + e.getMessage());
        }
    }
}
