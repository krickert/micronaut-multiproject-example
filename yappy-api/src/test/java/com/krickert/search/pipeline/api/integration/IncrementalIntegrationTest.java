package com.krickert.search.pipeline.api.integration;

import com.krickert.search.pipeline.api.dto.*;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.type.Argument;
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

/**
 * Incremental integration test that builds up functionality step by step:
 * 1. Basic cluster operations
 * 2. Module registration 
 * 3. Simple pipeline creation
 * 4. Multi-step pipelines
 * 5. End-to-end testing
 */
@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Property(name = "consul.client.host", value = "localhost") // Trigger consul test resource
@Property(name = "consul.client.port", value = "8500")
class IncrementalIntegrationTest {

    @Inject
    @Client("/api/v1")
    HttpClient client;

    @Inject
    Consul consul;

    private KeyValueClient kvClient;

    @BeforeAll
    static void beforeAll() {
        System.out.println("🚀 Starting incremental integration tests...");
    }

    @BeforeEach
    void setup() {
        kvClient = consul.keyValueClient();
        // Clean up any existing test data
        cleanupTestData();
    }

    @AfterEach
    void cleanup() {
        cleanupTestData();
    }

    // ========================================
    // STEP 1: Basic Cluster Operations
    // ========================================

    @Test
    @Order(1)
    @DisplayName("Step 1a: Test basic environment verification")
    void testBasicEnvironmentVerification() {
        System.out.println("🔍 Testing basic environment verification...");
        
        // Test that we can reach the test utilities endpoint
        HttpRequest<String> request = HttpRequest.GET("/test-utils/environment/verify");
        HttpResponse<EnvironmentStatus> response = client.toBlocking().exchange(request, EnvironmentStatus.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        EnvironmentStatus status = response.body();
        assertNotNull(status);
        
        System.out.println("✅ Environment verification passed: " + status);
    }

    @Test
    @Order(2)
    @DisplayName("Step 1b: Test KV store operations")
    void testBasicKvOperations() {
        System.out.println("🔍 Testing basic KV store operations...");
        
        // Clean KV store with test prefix
        HttpRequest<String> cleanRequest = HttpRequest.DELETE("/test-utils/kv/clean?prefix=test");
        HttpResponse<Void> cleanResponse = client.toBlocking().exchange(cleanRequest, Void.class);
        assertEquals(HttpStatus.OK, cleanResponse.getStatus());
        
        // Seed with basic test data (JSON object)
        Map<String, Object> testValue = Map.of("message", "test-value", "timestamp", System.currentTimeMillis());
        HttpRequest<Map<String, Object>> seedRequest = HttpRequest.POST("/test-utils/kv/seed?key=test/example", testValue);
        HttpResponse<Void> seedResponse = client.toBlocking().exchange(seedRequest, Void.class);
        assertEquals(HttpStatus.OK, seedResponse.getStatus());
        
        System.out.println("✅ KV operations passed");
    }

    @Test
    @Order(3)
    @DisplayName("Step 1c: Test schema operations")
    void testSchemaOperations() {
        System.out.println("🔍 Testing schema operations...");
        
        // Seed schemas
        HttpRequest<String> seedRequest = HttpRequest.POST("/test-utils/schemas/seed", "");
        HttpResponse<Void> seedResponse = client.toBlocking().exchange(seedRequest, Void.class);
        assertEquals(HttpStatus.OK, seedResponse.getStatus());
        
        System.out.println("✅ Schema operations passed");
    }

    // ========================================
    // STEP 2: Module Registration
    // ========================================

    @Test
    @Order(10)
    @DisplayName("Step 2a: Register a test module")
    void testModuleRegistration() {
        System.out.println("🔧 Testing module registration...");
        
        ModuleRegistrationRequest request = new ModuleRegistrationRequest(
            "test-echo-module",
            "Test Echo Module",
            "Echo module for testing",
            "1.0.0",
            "localhost",
            9090,
            List.of("echo", "test"),
            List.of("text/plain"),
            List.of("text/plain"),
            Map.of("version", "1.0.0"),
            "/health",
            Map.of("environment", "test")
        );

        HttpRequest<ModuleRegistrationRequest> httpRequest = HttpRequest.POST("/modules/register", request);
        HttpResponse<ModuleInfo> response = client.toBlocking().exchange(httpRequest, ModuleInfo.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        ModuleInfo module = response.body();
        assertNotNull(module);
        assertEquals("test-echo-module", module.serviceId());
        
        System.out.println("✅ Module registration passed: " + module.serviceId());
    }

    @Test
    @Order(11)
    @DisplayName("Step 2b: List registered modules")
    void testListModules() {
        System.out.println("🔍 Testing module listing...");
        
        HttpRequest<String> request = HttpRequest.GET("/modules");
        HttpResponse<List<ModuleInfo>> response = client.toBlocking().exchange(
            request, 
            Argument.listOf(ModuleInfo.class)
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        List<ModuleInfo> modules = response.body();
        assertNotNull(modules);
        
        System.out.println("✅ Module listing passed, found " + modules.size() + " modules");
    }

    // ========================================
    // STEP 3: Simple Pipeline Creation
    // ========================================

    @Test
    @Order(15)
    @DisplayName("Step 2c: Initialize default cluster configuration")
    void testInitializeClusterConfig() {
        System.out.println("🏗️ Initializing default cluster configuration...");
        
        // Use the test utilities to seed the cluster configuration
        Map<String, Object> clusterConfig = Map.of(
            "clusterName", "default",
            "description", "Default test cluster",
            "enabled", true,
            "pipelines", Map.of()
        );
        
        HttpRequest<Map<String, Object>> request = HttpRequest.POST("/test-utils/kv/seed?key=clusters/default", clusterConfig);
        HttpResponse<Void> response = client.toBlocking().exchange(request, Void.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        
        System.out.println("✅ Cluster configuration initialized");
    }

    @Test
    @Order(20)
    @DisplayName("Step 3a: Create a simple two-step pipeline")
    void testCreateSimplePipeline() {
        System.out.println("🏗️ Testing simple pipeline creation...");
        
        CreatePipelineRequest request = new CreatePipelineRequest(
            "simple-test-pipeline",
            "Simple Test Pipeline",
            "A two-step pipeline for testing",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "echo-step",
                    "test-echo-module",
                    Map.of("message", "Hello World"),
                    List.of("final-step") // Connect to final step
                ),
                new CreatePipelineRequest.StepDefinition(
                    "final-step",
                    "test-echo-module",
                    Map.of("message", "Goodbye World"),
                    null // No next steps (terminal)
                )
            ),
            List.of("test"),
            Map.of("environment", "test")
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("simple-test-pipeline", pipeline.id());
        assertEquals(2, pipeline.steps().size());
        
        System.out.println("✅ Simple pipeline creation passed: " + pipeline.id());
    }

    @Test
    @Order(21)
    @DisplayName("Step 3b: Retrieve the created pipeline")
    void testRetrievePipeline() {
        System.out.println("🔍 Testing pipeline retrieval...");
        
        // First ensure the pipeline exists by creating it
        createTestPipelineIfNotExists("simple-test-pipeline");
        
        // Wait a moment for the pipeline to be fully available
        try {
            Thread.sleep(100); // 100ms delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Now retrieve it
        HttpRequest<String> request = HttpRequest.GET("/pipelines/simple-test-pipeline");
        HttpResponse<PipelineView> response = client.toBlocking().exchange(request, PipelineView.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("simple-test-pipeline", pipeline.id());
        
        System.out.println("✅ Pipeline retrieval passed: " + pipeline.id());
    }

    @Test
    @Order(22)
    @DisplayName("Step 3c: List all pipelines")
    void testListPipelines() {
        System.out.println("🔍 Testing pipeline listing...");
        
        HttpRequest<String> request = HttpRequest.GET("/pipelines");
        HttpResponse<List<PipelineSummary>> response = client.toBlocking().exchange(
            request, 
            Argument.listOf(PipelineSummary.class)
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        List<PipelineSummary> pipelines = response.body();
        assertNotNull(pipelines);
        assertTrue(pipelines.size() > 0);
        
        System.out.println("✅ Pipeline listing passed, found " + pipelines.size() + " pipelines");
    }

    // ========================================
    // Helper Methods
    // ========================================

    private void createTestPipelineIfNotExists(String pipelineId) {
        try {
            // Try to get the pipeline first
            HttpRequest<String> getRequest = HttpRequest.GET("/pipelines/" + pipelineId);
            client.toBlocking().exchange(getRequest, PipelineView.class);
            // If we get here, pipeline already exists
            System.out.println("✅ Pipeline " + pipelineId + " already exists");
        } catch (Exception e) {
            // Pipeline doesn't exist, create it
            CreatePipelineRequest request = new CreatePipelineRequest(
                pipelineId,
                "Test Pipeline",
                "A test pipeline for integration testing",
                List.of(
                    new CreatePipelineRequest.StepDefinition(
                        "echo-step",
                        "test-echo-module",
                        Map.of("message", "Hello World"),
                        List.of("final-step")
                    ),
                    new CreatePipelineRequest.StepDefinition(
                        "final-step",
                        "test-echo-module",
                        Map.of("message", "Goodbye World"),
                        null
                    )
                ),
                List.of("test"),
                Map.of("environment", "test")
            );

            HttpRequest<CreatePipelineRequest> createRequest = HttpRequest.POST("/pipelines", request);
            client.toBlocking().exchange(createRequest, PipelineView.class);
            System.out.println("✅ Created pipeline " + pipelineId + " for testing");
        }
    }

    private void cleanupTestData() {
        try {
            // Clean up test pipelines
            String pipelinePrefix = "clusters/default/pipelines/";
            var pipelineKeys = kvClient.getKeys(pipelinePrefix);
            pipelineKeys.forEach(key -> {
                if (key.contains("test-") || key.contains("simple-test-")) {
                    try {
                        kvClient.deleteKey(key);
                    } catch (Exception e) {
                        // Ignore cleanup errors
                    }
                }
            });
            
            // Clean up test modules (if there's a modules prefix)
            try {
                kvClient.deleteKey("modules/test-echo-module");
            } catch (Exception e) {
                // Ignore cleanup errors
            }
            
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }
}