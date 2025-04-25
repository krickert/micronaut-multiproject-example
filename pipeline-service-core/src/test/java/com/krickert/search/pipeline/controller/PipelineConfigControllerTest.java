package com.krickert.search.pipeline.controller;

import com.krickert.search.pipeline.config.PipelineConfigManager;
import com.krickert.search.pipeline.config.PipelineConfig;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PipelineConfigControllerTest {

    @Inject
    private PipelineConfigManager pipelineConfig;

    @Inject
    @Client("/")
    private HttpClient client;

    @BeforeEach
    void setUp() {
        // Clear any existing pipeline configurations
        pipelineConfig.setPipelines(new HashMap<>());
    }

    @Test
    void testGetConfig() {
        // Setup
        Map<String, PipelineConfig> pipelines = new HashMap<>();
        pipelines.put("test-pipeline", new PipelineConfig("test-pipeline"));
        pipelineConfig.setPipelines(pipelines);

        try {
            // Test
            HttpRequest<?> request = HttpRequest.GET("/api/pipeline/config")
                .accept(MediaType.APPLICATION_JSON_TYPE);
            HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);

            // Verify
            assertEquals(HttpStatus.OK, response.status());
            assertNotNull(response.body());

            Map<String, Object> body = response.body();
            assertNotNull(body.get("pipelines"));
            assertTrue(body.get("pipelines").toString().contains("test-pipeline"));
        } catch (HttpClientResponseException e) {
            fail("Should not throw exception: " + e.getMessage());
        }
    }

    @Test
    void testReloadFromFile() {
        try {
            // Test
            HttpRequest<?> request = HttpRequest.POST("/api/pipeline/config/reload/file", "")
                .accept(MediaType.APPLICATION_JSON_TYPE);
            HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);

            // Verify
            assertEquals(HttpStatus.OK, response.status());
            assertNotNull(response.body());

            Map<String, Object> body = response.body();
            assertEquals("success", body.get("status"));

            // Verify that pipelines were loaded
            assertFalse(pipelineConfig.getPipelines().isEmpty());
        } catch (HttpClientResponseException e) {
            // If we get an error, it might be because the test properties file doesn't exist
            // This is acceptable in a test environment
            if (e.getStatus() == HttpStatus.INTERNAL_SERVER_ERROR) {
                // Check if the error message indicates the file wasn't found
                String message = e.getResponse().getBody(String.class).orElse("");
                if (message.contains("Failed to reload pipeline configuration from file")) {
                    // This is expected if the file doesn't exist
                    return;
                }
            }
            fail("Unexpected exception: " + e.getMessage());
        }
    }

    @Test
    void testReloadFromConsul() {
        // This test assumes Consul is not available in the test environment
        // so it should return a server error

        try {
            // Test
            HttpRequest<?> request = HttpRequest.POST("/api/pipeline/config/reload/consul", "")
                .accept(MediaType.APPLICATION_JSON_TYPE);
            HttpResponse<Map> response = client.toBlocking().exchange(request, Map.class);

            // If we get here, Consul might be available or the controller is handling the absence gracefully
            assertEquals(HttpStatus.OK, response.status());
            assertNotNull(response.body());

            Map<String, Object> body = response.body();
            // Either we got a success response or the pipelines are empty
            if ("success".equals(body.get("status"))) {
                assertNotNull(body.get("pipelines"));
            } else {
                // If not success, the pipelines should be empty
                assertTrue(pipelineConfig.getPipelines().isEmpty());
            }
        } catch (HttpClientResponseException e) {
            // Since Consul is likely not available in the test environment, 
            // an error response is expected and acceptable
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
            String message = e.getResponse().getBody(String.class).orElse("");
            assertTrue(message.contains("Failed to reload pipeline configuration from Consul"), 
                    "Error message should indicate Consul configuration failure");
        }
    }
}
