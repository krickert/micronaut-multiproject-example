package com.krickert.search.pipeline.api.integration;

import com.krickert.search.pipeline.api.dto.*;
import com.krickert.search.config.pipeline.model.*;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
@Property(name = "kafka.enabled", value = "true")
class IncrementalIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(IncrementalIntegrationTest.class);

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

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
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
        
        // Now retrieve it (explicitly specify cluster parameter)
        HttpRequest<String> request = HttpRequest.GET("/pipelines/simple-test-pipeline?cluster=default");
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
    // STEP 4: Kafka Topic Creation 
    // ========================================

    // Kafka tests disabled - requires engine dependencies
    /*
    @Test
    @Order(30)
    @DisplayName("Step 4a: Create pipeline with Kafka inputs and verify topic creation")
    void testKafkaTopicCreationForPipeline() {
        System.out.println("🏗️ Testing Kafka topic creation for pipeline...");
        
        // Create a pipeline with Kafka inputs
        CreatePipelineRequest request = new CreatePipelineRequest(
            "kafka-test-pipeline",
            "Kafka Test Pipeline",
            "Pipeline with Kafka inputs to test topic creation",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "kafka-input-step",
                    "test-echo-module",
                    Map.of(
                        "kafkaInputs", List.of(Map.of(
                            "listenTopics", List.of("test-input-topic"),
                            "consumerGroupId", "test-consumer-group"
                        ))
                    ),
                    List.of("output-step")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "output-step",
                    "test-echo-module",
                    Map.of("message", "Processing Kafka data"),
                    null
                )
            ),
            List.of("kafka", "test"),
            Map.of("environment", "test")
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("kafka-test-pipeline", pipeline.id());
        
        // Wait for topic creation
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    System.out.println("Checking topic creation count: " + topicCreationListener.getCreatedTopicsCount());
                    return topicCreationListener.getCreatedTopicsCount() >= 1;
                });
        
        // Verify topics were created
        List<String> topics = topicService.listTopicsForStep("kafka-test-pipeline", "kafka-input-step")
                .block();
        assertNotNull(topics);
        assertTrue(topics.size() > 0, "Topics should be created for Kafka-enabled step");
        
        System.out.println("✅ Kafka topic creation passed. Created topics: " + topics);
    }

    @Test
    @Order(31)
    @DisplayName("Step 4b: Update pipeline without changing Kafka config - no duplicate topics")
    void testNoDuplicateTopicCreation() {
        System.out.println("🔍 Testing that pipeline updates don't create duplicate topics...");
        
        // First ensure the kafka-test-pipeline exists
        createKafkaPipelineIfNotExists();
        
        int initialTopicCount = topicCreationListener.getCreatedTopicsCount();
        System.out.println("Initial topic count: " + initialTopicCount);
        
        // Update the pipeline metadata only (not Kafka config)
        UpdatePipelineRequest updateRequest = new UpdatePipelineRequest(
            "Kafka Test Pipeline - Updated",
            "Updated description for Kafka test pipeline",
            List.of("kafka", "test", "updated"),
            null, // Don't update steps
            Map.of("environment", "test", "updated", "true")
        );

        HttpRequest<UpdatePipelineRequest> httpRequest = HttpRequest.PUT("/pipelines/kafka-test-pipeline?cluster=default", updateRequest);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Wait a bit to ensure any async processing completes
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Verify no new topics were created
        int finalTopicCount = topicCreationListener.getCreatedTopicsCount();
        assertEquals(initialTopicCount, finalTopicCount, 
                "Topic count should remain the same after updating pipeline without changing Kafka config");
        
        System.out.println("✅ No duplicate topics created. Final topic count: " + finalTopicCount);
    }
    */

    // ========================================
    // STEP 5: Whitelist Management
    // ========================================
    
    @Test
    @Order(40)
    @DisplayName("Step 5a: Add gRPC service to whitelist")
    void testAddGrpcServiceToWhitelist() {
        System.out.println("🔐 Testing gRPC service whitelisting...");
        
        // Add the test-echo-module service to the whitelist
        HttpRequest<String> request = HttpRequest.POST(
            "/clusters/default/whitelist/grpc-services/test-echo-module", ""
        );
        HttpResponse<Void> response = client.toBlocking().exchange(request);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify it was added
        HttpRequest<String> getRequest = HttpRequest.GET("/clusters/default/whitelist/grpc-services");
        HttpResponse<Set<String>> getResponse = client.toBlocking().exchange(
            getRequest, 
            Argument.setOf(String.class)
        );
        
        assertEquals(HttpStatus.OK, getResponse.getStatus());
        Set<String> whitelistedServices = getResponse.body();
        assertNotNull(whitelistedServices);
        assertTrue(whitelistedServices.contains("test-echo-module"));
        
        System.out.println("✅ gRPC service whitelisting passed: " + whitelistedServices);
    }
    
    @Test
    @Order(41)
    @DisplayName("Step 5b: Add Kafka topics to whitelist")
    void testAddKafkaTopicsToWhitelist() {
        System.out.println("🔐 Testing Kafka topic whitelisting...");
        
        // Add some Kafka topics following the naming convention
        String[] topics = {
            "yappy.pipeline.simple-test-pipeline.step.echo-step.output",
            "yappy.pipeline.simple-test-pipeline.step.final-step.input"
        };
        
        for (String topic : topics) {
            HttpRequest<String> request = HttpRequest.POST(
                "/clusters/default/whitelist/kafka-topics/" + topic, ""
            );
            HttpResponse<Void> response = client.toBlocking().exchange(request);
            assertEquals(HttpStatus.OK, response.getStatus());
        }
        
        // Verify they were added
        HttpRequest<String> getRequest = HttpRequest.GET("/clusters/default/whitelist/kafka-topics");
        HttpResponse<Set<String>> getResponse = client.toBlocking().exchange(
            getRequest, 
            Argument.setOf(String.class)
        );
        
        assertEquals(HttpStatus.OK, getResponse.getStatus());
        Set<String> whitelistedTopics = getResponse.body();
        assertNotNull(whitelistedTopics);
        assertTrue(whitelistedTopics.containsAll(Arrays.asList(topics)));
        
        System.out.println("✅ Kafka topic whitelisting passed: " + whitelistedTopics);
    }
    
    @Test
    @Order(42)
    @DisplayName("Step 5c: Update entire whitelist")
    void testUpdateWhitelist() {
        System.out.println("🔐 Testing whitelist bulk update...");
        
        Set<String> newServices = Set.of("test-echo-module", "test-chunker-module", "test-embedder-module");
        
        HttpRequest<Set<String>> request = HttpRequest.PUT(
            "/clusters/default/whitelist/grpc-services", newServices
        );
        HttpResponse<Set<String>> response = client.toBlocking().exchange(
            request, 
            Argument.setOf(String.class)
        );
        
        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(newServices, response.body());
        
        System.out.println("✅ Whitelist bulk update passed: " + newServices);
    }
    
    @Test
    @Order(43)
    @DisplayName("Step 5d: Remove from whitelist")
    void testRemoveFromWhitelist() {
        System.out.println("🔐 Testing whitelist removal...");
        
        // Remove one of the services
        HttpRequest<String> request = HttpRequest.DELETE(
            "/clusters/default/whitelist/grpc-services/test-embedder-module"
        );
        HttpResponse<Void> response = client.toBlocking().exchange(request);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        
        // Verify it was removed
        HttpRequest<String> getRequest = HttpRequest.GET("/clusters/default/whitelist/grpc-services");
        HttpResponse<Set<String>> getResponse = client.toBlocking().exchange(
            getRequest, 
            Argument.setOf(String.class)
        );
        
        Set<String> whitelistedServices = getResponse.body();
        assertNotNull(whitelistedServices);
        assertFalse(whitelistedServices.contains("test-embedder-module"));
        assertTrue(whitelistedServices.contains("test-echo-module"));
        
        System.out.println("✅ Whitelist removal passed: " + whitelistedServices);
    }

    // ========================================
    // STEP 6: Pipeline Step Management
    // ========================================
    
    @Test
    @Order(50)
    @DisplayName("Step 6a: Get details of a specific pipeline step")
    void testGetPipelineStep() {
        System.out.println("🔍 Testing pipeline step retrieval...");
        
        // First ensure the pipeline exists
        createTestPipelineIfNotExists("simple-test-pipeline");
        
        HttpRequest<String> request = HttpRequest.GET("/pipelines/simple-test-pipeline/steps/echo-step?cluster=default");
        HttpResponse<PipelineStepView> response = client.toBlocking().exchange(request, PipelineStepView.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        PipelineStepView step = response.body();
        assertNotNull(step);
        assertEquals("echo-step", step.id());
        assertEquals("test-echo-module", step.module());
        assertNotNull(step.kafkaTopics());
        assertEquals("yappy.pipeline.simple-test-pipeline.step.echo-step.input", step.kafkaTopics().inputTopic());
        
        System.out.println("✅ Pipeline step retrieval passed: " + step.id());
    }
    
    @Test
    @Order(51)
    @DisplayName("Step 6b: Add a new step to an existing pipeline")
    void testAddPipelineStep() {
        System.out.println("🔧 Testing pipeline step addition...");
        
        // First ensure the pipeline exists
        createTestPipelineIfNotExists("simple-test-pipeline");
        
        AddPipelineStepRequest request = new AddPipelineStepRequest(
            "enrichment-step",
            "test-echo-module",
            Map.of("enrichmentType", "metadata"),
            List.of("final-step"),
            List.of("echo-step"),
            2
        );
        
        HttpRequest<AddPipelineStepRequest> httpRequest = HttpRequest.POST(
            "/pipelines/simple-test-pipeline/steps?cluster=default", request
        );
        HttpResponse<PipelineStepView> response = client.toBlocking().exchange(httpRequest, PipelineStepView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineStepView newStep = response.body();
        assertNotNull(newStep);
        assertEquals("enrichment-step", newStep.id());
        assertEquals(List.of("echo-step"), newStep.previous());
        assertEquals(List.of("final-step"), newStep.next());
        
        System.out.println("✅ Pipeline step addition passed: " + newStep.id());
    }
    
    @Test
    @Order(52)
    @DisplayName("Step 6c: Update an existing pipeline step")
    void testUpdatePipelineStep() {
        System.out.println("🔧 Testing pipeline step update...");
        
        // First ensure the pipeline and enrichment step exist
        createTestPipelineIfNotExists("simple-test-pipeline");
        // Try to ensure enrichment step exists, but handle if it already exists
        try {
            testAddPipelineStep(); // Ensure enrichment step exists
        } catch (Exception e) {
            // If it fails, likely the step already exists, which is fine
            System.out.println("Enrichment step may already exist, continuing...");
        }
        
        UpdatePipelineStepRequest request = new UpdatePipelineStepRequest(
            null, // Don't change module
            Map.of("enrichmentType", "advanced", "includeStats", true),
            null, // Don't change connections
            null,
            null
        );
        
        HttpRequest<UpdatePipelineStepRequest> httpRequest = HttpRequest.PUT(
            "/pipelines/simple-test-pipeline/steps/enrichment-step?cluster=default", request
        );
        HttpResponse<PipelineStepView> response = client.toBlocking().exchange(httpRequest, PipelineStepView.class);
        
        assertEquals(HttpStatus.OK, response.getStatus());
        PipelineStepView updatedStep = response.body();
        assertNotNull(updatedStep);
        assertEquals("advanced", updatedStep.config().get("enrichmentType"));
        assertTrue((Boolean) updatedStep.config().get("includeStats"));
        
        System.out.println("✅ Pipeline step update passed");
    }
    
    @Test
    @Order(53)
    @DisplayName("Step 6d: Remove a step from a pipeline")
    void testRemovePipelineStep() {
        System.out.println("🗑️ Testing pipeline step removal...");
        
        // First create a pipeline with a removable step
        CreatePipelineRequest pipelineRequest = new CreatePipelineRequest(
            "test-removal-pipeline",
            "Test Removal Pipeline",
            "Pipeline for testing step removal",
            List.of(
                new CreatePipelineRequest.StepDefinition(
                    "step-1",
                    "test-echo-module",
                    Map.of(),
                    List.of("step-2")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "step-2",
                    "test-echo-module",
                    Map.of(),
                    List.of("step-3")
                ),
                new CreatePipelineRequest.StepDefinition(
                    "step-3",
                    "test-echo-module",
                    Map.of(),
                    null // Terminal step
                )
            ),
            List.of("test"),
            Map.of()
        );
        
        HttpRequest<CreatePipelineRequest> createRequest = HttpRequest.POST("/pipelines?cluster=default", pipelineRequest);
        client.toBlocking().exchange(createRequest, PipelineView.class);
        
        // Now remove the terminal step (step-3)
        HttpRequest<String> deleteRequest = HttpRequest.DELETE("/pipelines/test-removal-pipeline/steps/step-3?cluster=default");
        HttpResponse<Void> response = client.toBlocking().exchange(deleteRequest);
        
        assertEquals(HttpStatus.NO_CONTENT, response.getStatus());
        
        // Verify the step was removed
        HttpRequest<String> getRequest = HttpRequest.GET("/pipelines/test-removal-pipeline?cluster=default");
        HttpResponse<PipelineView> getResponse = client.toBlocking().exchange(getRequest, PipelineView.class);
        PipelineView pipeline = getResponse.body();
        assertNotNull(pipeline);
        assertEquals(2, pipeline.steps().size());
        assertFalse(pipeline.steps().stream().anyMatch(s -> "step-3".equals(s.id())));
        
        System.out.println("✅ Pipeline step removal passed");
    }
    
    @Test
    @Order(54)
    @DisplayName("Step 6e: Try to add a step with duplicate ID (should fail)")
    void testAddDuplicateStepId() {
        System.out.println("🚫 Testing duplicate step ID prevention...");
        
        // First ensure the pipeline exists
        createTestPipelineIfNotExists("simple-test-pipeline");
        
        AddPipelineStepRequest request = new AddPipelineStepRequest(
            "echo-step", // This ID already exists
            "test-echo-module",
            Map.of(),
            List.of("final-step"),
            List.of(),
            1
        );
        
        HttpRequest<AddPipelineStepRequest> httpRequest = HttpRequest.POST(
            "/pipelines/simple-test-pipeline/steps?cluster=default", request
        );
        
        try {
            client.toBlocking().exchange(httpRequest, PipelineStepView.class);
            fail("Should have thrown exception for duplicate step ID");
        } catch (Exception e) {
            // Expected
            System.out.println("✅ Duplicate step ID prevention passed");
        }
    }
    
    @Test
    @Order(55)
    @DisplayName("Step 6f: Try to remove a step with dependencies (should fail)")
    void testRemoveStepWithDependencies() {
        System.out.println("🚫 Testing dependency check on step removal...");
        
        // First ensure the pipeline exists
        createTestPipelineIfNotExists("simple-test-pipeline");
        
        // Try to remove echo-step which has final-step depending on it
        HttpRequest<String> deleteRequest = HttpRequest.DELETE("/pipelines/simple-test-pipeline/steps/echo-step?cluster=default");
        
        try {
            client.toBlocking().exchange(deleteRequest);
            fail("Should have thrown exception for removing step with dependencies");
        } catch (Exception e) {
            // Expected
            System.out.println("✅ Dependency check on step removal passed");
        }
    }

    @Test
    @Order(56)
    @DisplayName("Step 6g: Test config handling for simple string vs complex configs")
    void testConfigHandling() {
        System.out.println("🔧 Testing config handling for simple vs complex configurations...");
        
        // Create a pipeline with different config types
        CreatePipelineRequest request = new CreatePipelineRequest(
            "config-test-pipeline",
            "Config Test Pipeline",
            "Testing different config types",
            List.of(
                // Step with simple string configs only
                new CreatePipelineRequest.StepDefinition(
                    "simple-config-step",
                    "test-echo-module",
                    Map.of("message", "Hello", "format", "plain", "level", "info"),
                    List.of("complex-config-step")
                ),
                // Step with complex nested configs
                new CreatePipelineRequest.StepDefinition(
                    "complex-config-step",
                    "test-echo-module",
                    Map.of(
                        "message", "Hello",
                        "options", Map.of("nested", "value", "deep", Map.of("level", 2)),
                        "array", List.of("item1", "item2")
                    ),
                    List.of("no-config-step")
                ),
                // Step with no config (should get empty defaults)
                new CreatePipelineRequest.StepDefinition(
                    "no-config-step",
                    "test-echo-module",
                    null,
                    null
                )
            ),
            List.of("test"),
            Map.of()
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("config-test-pipeline", pipeline.id());
        assertEquals(3, pipeline.steps().size());
        
        // Debug: print all steps
        LOG.debug("All steps:");
        pipeline.steps().forEach(step -> {
            LOG.debug("  Step ID: {}, Config: {}", step.id(), step.config());
        });
        
        // Verify the simple config step (internally uses configParams)
        var simpleStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("simple-config-step"))
            .findFirst()
            .orElseThrow();
        LOG.debug("Simple step config: {}", simpleStep.config());
        assertNotNull(simpleStep.config(), "Simple step config should not be null");
        assertEquals("Hello", simpleStep.config().get("message"));
        assertEquals("plain", simpleStep.config().get("format"));
        assertEquals("info", simpleStep.config().get("level"));
        
        // Verify the complex config step (internally uses jsonConfig)
        var complexStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("complex-config-step"))
            .findFirst()
            .orElseThrow();
        assertEquals("Hello", complexStep.config().get("message"));
        assertNotNull(complexStep.config().get("options"));
        assertNotNull(complexStep.config().get("array"));
        
        // Verify the no-config step (should have null config when no config is provided)
        var noConfigStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("no-config-step"))
            .findFirst()
            .orElseThrow();
        
        // For steps with no config, we expect null config to avoid empty map serialization issues
        assertNull(noConfigStep.config());
        
        System.out.println("✅ Config handling test passed - simple configs use configParams, complex use jsonConfig");
    }

    // ========================================
    // Helper Methods
    // ========================================

    /* Kafka helper disabled - requires engine dependencies
    private void createKafkaPipelineIfNotExists() {
        try {
            // Try to get the pipeline first
            HttpRequest<String> getRequest = HttpRequest.GET("/pipelines/kafka-test-pipeline?cluster=default");
            client.toBlocking().exchange(getRequest, PipelineView.class);
            // If we get here, pipeline already exists
            System.out.println("✅ Kafka test pipeline already exists");
        } catch (Exception e) {
            // Pipeline doesn't exist, create it
            testKafkaTopicCreationForPipeline();
        }
    }
    */

    private void createTestPipelineIfNotExists(String pipelineId) {
        try {
            // Try to get the pipeline first
            HttpRequest<String> getRequest = HttpRequest.GET("/pipelines/" + pipelineId + "?cluster=default");
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

            HttpRequest<CreatePipelineRequest> createRequest = HttpRequest.POST("/pipelines?cluster=default", request);
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