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
@Property(name = "consul.client.host") // Trigger consul test resource
@Property(name = "consul.client.port")
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
        LOG.info("🚀 Starting incremental integration tests...");
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
        
        LOG.info("✅ Environment verification passed: " + status);
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
    
    @Test
    @DisplayName("Step 7: Multi-step pipeline integration test")
    @Order(7)
    void testMultiStepPipeline() throws Exception {
        System.out.println("🔗 Testing multi-step pipeline with real step connections...");
        
        // Create a 3-step pipeline: tika-parser → chunker → embedder
        var request = new CreatePipelineRequest(
            "multi-step-pipeline",
            "Multi-Step Document Processing Pipeline", 
            "Full document processing: parse → chunk → embed",
            List.of(
                // Step 1: Document parsing (entry point)
                new CreatePipelineRequest.StepDefinition(
                    "parse-documents",
                    "tika-parser",
                    Map.of(
                        "maxFileSize", "50MB",
                        "extractMetadata", "true",
                        "outputFormat", "text"
                    ),
                    List.of("chunk-text") // → chunk-text
                ),
                // Step 2: Text chunking (middle step)
                new CreatePipelineRequest.StepDefinition(
                    "chunk-text", 
                    "chunker",
                    Map.of(
                        "chunkSize", "1000",
                        "overlap", "100", 
                        "strategy", "semantic"
                    ),
                    List.of("generate-embeddings") // → generate-embeddings
                ),
                // Step 3: Embedding generation (terminal step)
                new CreatePipelineRequest.StepDefinition(
                    "generate-embeddings",
                    "embedder",
                    Map.of(
                        "model", "text-embedding-ada-002",
                        "dimensions", "1536",
                        "batchSize", "10",
                        "settings", Map.of(
                            "temperature", 0.0,
                            "maxTokens", 8192,
                            "provider", Map.of("name", "openai", "region", "us-east-1")
                        )
                    ),
                    null // Terminal step - no next steps
                )
            ),
            List.of("nlp", "production", "multi-step"),
            Map.of("version", "1.0", "priority", "high")
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("multi-step-pipeline", pipeline.id());
        assertEquals("Multi-Step Document Processing Pipeline", pipeline.name());
        assertEquals(3, pipeline.steps().size());
        
        LOG.info("Created multi-step pipeline with {} steps", pipeline.steps().size());
        
        // Verify step 1: parse-documents (INITIAL_PIPELINE)
        var parseStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("parse-documents"))
            .findFirst()
            .orElseThrow();
        assertEquals("tika-parser", parseStep.module());
        assertNotNull(parseStep.config());
        assertEquals("50MB", parseStep.config().get("maxFileSize"));
        assertEquals("true", parseStep.config().get("extractMetadata"));
        assertEquals(List.of("chunk-text"), parseStep.next());
        LOG.info("✓ Parse step configured correctly with config: {}", parseStep.config());
        
        // Verify step 2: chunk-text (PIPELINE)  
        var chunkStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("chunk-text"))
            .findFirst()
            .orElseThrow();
        assertEquals("chunker", chunkStep.module());
        assertNotNull(chunkStep.config());
        assertEquals("1000", chunkStep.config().get("chunkSize"));
        assertEquals("semantic", chunkStep.config().get("strategy"));
        assertEquals(List.of("generate-embeddings"), chunkStep.next());
        LOG.info("✓ Chunk step configured correctly with config: {}", chunkStep.config());
        
        // Verify step 3: generate-embeddings (SINK)
        var embedStep = pipeline.steps().stream()
            .filter(s -> s.id().equals("generate-embeddings"))
            .findFirst()
            .orElseThrow();
        assertEquals("embedder", embedStep.module());
        assertNotNull(embedStep.config());
        assertEquals("text-embedding-ada-002", embedStep.config().get("model"));
        assertEquals("1536", embedStep.config().get("dimensions"));
        // Verify complex nested config
        assertNotNull(embedStep.config().get("settings"));
        @SuppressWarnings("unchecked")
        var settings = (Map<String, Object>) embedStep.config().get("settings");
        assertEquals(0.0, settings.get("temperature"));
        assertNotNull(settings.get("provider"));
        assertTrue(embedStep.next() == null || embedStep.next().isEmpty());
        LOG.info("✓ Embedding step configured correctly with complex config: {}", embedStep.config());
        
        // Verify pipeline flow connectivity
        // parse-documents → chunk-text → generate-embeddings
        assertTrue(parseStep.next().contains("chunk-text"), "Parse step should connect to chunk step");
        assertTrue(chunkStep.next().contains("generate-embeddings"), "Chunk step should connect to embedding step");
        assertTrue(embedStep.next() == null || embedStep.next().isEmpty(), "Embedding step should be terminal");
        
        // Verify the pipeline was stored correctly in Consul by retrieving it
        HttpRequest<Void> getRequest = HttpRequest.GET("/pipelines/multi-step-pipeline?cluster=default");
        HttpResponse<PipelineView> getResponse = client.toBlocking().exchange(getRequest, PipelineView.class);
        assertEquals(HttpStatus.OK, getResponse.getStatus());
        
        PipelineView retrievedPipeline = getResponse.body();
        assertNotNull(retrievedPipeline);
        assertEquals(pipeline.id(), retrievedPipeline.id());
        assertEquals(pipeline.steps().size(), retrievedPipeline.steps().size());
        LOG.info("✓ Pipeline successfully stored and retrieved from Consul");
        
        // Test step retrieval API
        HttpRequest<Void> stepRequest = HttpRequest.GET("/pipelines/multi-step-pipeline/steps/chunk-text?cluster=default");
        HttpResponse<PipelineStepView> stepResponse = client.toBlocking().exchange(stepRequest, PipelineStepView.class);
        assertEquals(HttpStatus.OK, stepResponse.getStatus());
        
        PipelineStepView retrievedStep = stepResponse.body();
        assertNotNull(retrievedStep);
        assertEquals("chunk-text", retrievedStep.id());
        assertEquals("chunker", retrievedStep.module());
        assertNotNull(retrievedStep.config());
        assertEquals("1000", retrievedStep.config().get("chunkSize"));
        LOG.info("✓ Individual step retrieval working correctly");
        
        System.out.println("✅ Multi-step pipeline test passed - 3-step pipeline with real connections working!");
    }
    
    @Test
    @DisplayName("Step 8: Complex pipeline scenarios - fan-out and fan-in")
    @Order(8)
    void testComplexPipelineScenarios() throws Exception {
        System.out.println("🌊 Testing complex pipeline with fan-out and fan-in patterns...");
        
        // Create a complex pipeline with branching:
        // parse-docs → [chunk-large, chunk-small] → [embed-v1, embed-v2] → merge-results
        var request = new CreatePipelineRequest(
            "complex-branching-pipeline",
            "Complex Document Processing with Multiple Paths",
            "Demonstrates fan-out and fan-in: parse → multiple chunkers → multiple embedders → merge",
            List.of(
                // Step 1: Document parsing (entry point - fan-out source)
                new CreatePipelineRequest.StepDefinition(
                    "parse-docs",
                    "tika-parser",
                    Map.of(
                        "extractImages", "true",
                        "extractTables", "true",
                        "maxFileSize", "100MB"
                    ),
                    List.of("chunk-large", "chunk-small") // FAN-OUT: → 2 chunkers
                ),
                
                // Step 2a: Large chunk processing (parallel path 1)
                new CreatePipelineRequest.StepDefinition(
                    "chunk-large",
                    "chunker",
                    Map.of(
                        "chunkSize", "2000",
                        "overlap", "200",
                        "strategy", "document-structure"
                    ),
                    List.of("embed-v1", "embed-v2") // FAN-OUT: → 2 embedders
                ),
                
                // Step 2b: Small chunk processing (parallel path 2)  
                new CreatePipelineRequest.StepDefinition(
                    "chunk-small",
                    "chunker",
                    Map.of(
                        "chunkSize", "500", 
                        "overlap", "50",
                        "strategy", "sentence-boundary"
                    ),
                    List.of("embed-v1", "embed-v2") // FAN-OUT: → 2 embedders
                ),
                
                // Step 3a: Primary embedding model (fan-in destination)
                new CreatePipelineRequest.StepDefinition(
                    "embed-v1",
                    "embedder",
                    Map.of(
                        "model", "text-embedding-ada-002",
                        "dimensions", "1536",
                        "provider", Map.of(
                            "name", "openai",
                            "apiVersion", "v1",
                            "rateLimit", Map.of("rpm", 3000, "tpm", 160000)
                        )
                    ),
                    List.of("merge-results") // FAN-IN: → merge step
                ),
                
                // Step 3b: Secondary embedding model (fan-in destination)
                new CreatePipelineRequest.StepDefinition(
                    "embed-v2", 
                    "embedder",
                    Map.of(
                        "model", "text-embedding-3-large",
                        "dimensions", "3072",
                        "provider", Map.of(
                            "name", "openai",
                            "apiVersion", "v1",
                            "experimental", true
                        )
                    ),
                    List.of("merge-results") // FAN-IN: → merge step
                ),
                
                // Step 4: Result aggregation (fan-in sink - receives from 2 embedders)
                new CreatePipelineRequest.StepDefinition(
                    "merge-results",
                    "result-merger",
                    Map.of(
                        "strategy", "ensemble",
                        "weights", Map.of(
                            "embed-v1", 0.7,
                            "embed-v2", 0.3
                        ),
                        "outputFormat", Map.of(
                            "includeMetadata", true,
                            "vectorFormat", "dense",
                            "compression", "none"
                        )
                    ),
                    null // Terminal step
                )
            ),
            List.of("complex", "branching", "production", "ensemble"),
            Map.of(
                "complexity", "high",
                "parallelPaths", 4,
                "totalSteps", 6
            )
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("complex-branching-pipeline", pipeline.id());
        assertEquals(6, pipeline.steps().size());
        
        LOG.info("Created complex branching pipeline with {} steps", pipeline.steps().size());
        
        // Verify FAN-OUT: parse-docs → [chunk-large, chunk-small]
        var parseStep = findStep(pipeline, "parse-docs");
        assertEquals("tika-parser", parseStep.module());
        assertEquals(2, parseStep.next().size());
        assertTrue(parseStep.next().contains("chunk-large"));
        assertTrue(parseStep.next().contains("chunk-small"));
        assertEquals("true", parseStep.config().get("extractImages"));
        LOG.info("✓ Fan-out from parse-docs: {} → {}", parseStep.id(), parseStep.next());
        
        // Verify parallel chunking paths
        var chunkLarge = findStep(pipeline, "chunk-large");
        var chunkSmall = findStep(pipeline, "chunk-small");
        assertEquals("chunker", chunkLarge.module());
        assertEquals("chunker", chunkSmall.module());
        assertEquals("2000", chunkLarge.config().get("chunkSize"));
        assertEquals("500", chunkSmall.config().get("chunkSize"));
        
        // Both chunkers fan-out to both embedders
        assertEquals(2, chunkLarge.next().size());
        assertEquals(2, chunkSmall.next().size());
        assertTrue(chunkLarge.next().contains("embed-v1") && chunkLarge.next().contains("embed-v2"));
        assertTrue(chunkSmall.next().contains("embed-v1") && chunkSmall.next().contains("embed-v2"));
        LOG.info("✓ Parallel chunker fan-out: large({}) & small({}) → embedders", 
                chunkLarge.config().get("chunkSize"), chunkSmall.config().get("chunkSize"));
        
        // Verify FAN-IN: [embed-v1, embed-v2] → merge-results
        var embedV1 = findStep(pipeline, "embed-v1");
        var embedV2 = findStep(pipeline, "embed-v2");
        assertEquals("embedder", embedV1.module());
        assertEquals("embedder", embedV2.module());
        assertEquals(List.of("merge-results"), embedV1.next());
        assertEquals(List.of("merge-results"), embedV2.next());
        
        // Verify complex nested configs in embedders
        assertEquals("text-embedding-ada-002", embedV1.config().get("model"));
        assertEquals("text-embedding-3-large", embedV2.config().get("model"));
        @SuppressWarnings("unchecked")
        var v1Provider = (Map<String, Object>) embedV1.config().get("provider");
        @SuppressWarnings("unchecked")
        var v2Provider = (Map<String, Object>) embedV2.config().get("provider");
        assertEquals("openai", v1Provider.get("name"));
        assertEquals(true, v2Provider.get("experimental"));
        LOG.info("✓ Fan-in to merge: embed-v1({}) & embed-v2({}) → merge-results", 
                embedV1.config().get("model"), embedV2.config().get("model"));
        
        // Verify terminal merge step with complex config
        var mergeStep = findStep(pipeline, "merge-results");
        assertEquals("result-merger", mergeStep.module());
        assertTrue(mergeStep.next() == null || mergeStep.next().isEmpty());
        assertEquals("ensemble", mergeStep.config().get("strategy"));
        @SuppressWarnings("unchecked")
        var weights = (Map<String, Object>) mergeStep.config().get("weights");
        assertEquals(0.7, weights.get("embed-v1"));
        assertEquals(0.3, weights.get("embed-v2"));
        LOG.info("✓ Terminal merge step with ensemble weights: {}", weights);
        
        // Test complex pipeline retrieval and step-level access
        HttpRequest<Void> getRequest = HttpRequest.GET("/pipelines/complex-branching-pipeline?cluster=default");
        HttpResponse<PipelineView> getResponse = client.toBlocking().exchange(getRequest, PipelineView.class);
        assertEquals(HttpStatus.OK, getResponse.getStatus());
        
        PipelineView retrieved = getResponse.body();
        assertEquals(6, retrieved.steps().size());
        LOG.info("✓ Complex pipeline stored and retrieved successfully");
        
        // Test individual step retrieval for a complex step
        HttpRequest<Void> stepRequest = HttpRequest.GET("/pipelines/complex-branching-pipeline/steps/embed-v1?cluster=default");
        HttpResponse<PipelineStepView> stepResponse = client.toBlocking().exchange(stepRequest, PipelineStepView.class);
        assertEquals(HttpStatus.OK, stepResponse.getStatus());
        
        PipelineStepView step = stepResponse.body();
        assertEquals("embed-v1", step.id());
        assertEquals("embedder", step.module());
        assertEquals("text-embedding-ada-002", step.config().get("model"));
        LOG.info("✓ Complex step retrieval working correctly");
        
        // Validate pipeline metadata and tags
        assertEquals(4, pipeline.tags().size());
        assertTrue(pipeline.tags().contains("complex"));
        assertTrue(pipeline.tags().contains("branching"));
        assertTrue(pipeline.tags().contains("ensemble"));
        LOG.info("✓ Pipeline tags validated: {}", pipeline.tags());
        
        System.out.println("✅ Complex pipeline scenarios test passed - fan-out/fan-in with real branching!");
        System.out.println("   📊 Pipeline flow: parse → [2 chunkers] → [2 embedders] → merge");
        System.out.println("   🌟 Total parallel paths: 4, convergence points: 2");
    }
    
    @Test
    @DisplayName("Step 9: Async pipeline configuration tests")
    @Order(9)
    void testAsyncPipelineConfiguration() throws Exception {
        LOG.info("🎯 Testing async pipeline configuration features...");
        
        // Create a pipeline with async-focused configuration
        var request = new CreatePipelineRequest(
            "async-config-pipeline",
            "Async Configuration Test Pipeline",
            "Tests async processing configuration and multi-step flows",
            List.of(
                // Step 1: Input processor with async config
                new CreatePipelineRequest.StepDefinition(
                    "input-processor",
                    "test-echo-module",
                    Map.of(
                        "processingMode", "async",
                        "batchSize", "5",
                        "timeout", "30s",
                        "bufferSize", "1000"
                    ),
                    List.of("message-router")
                ),
                // Step 2: Message router (fan-out)
                new CreatePipelineRequest.StepDefinition(
                    "message-router",
                    "test-echo-module",
                    Map.of(
                        "routingStrategy", "round-robin",
                        "maxRetries", "3",
                        "retryDelay", "1s",
                        "loadBalancing", Map.of(
                            "algorithm", "weighted",
                            "weights", Map.of("processor-1", 0.6, "processor-2", 0.4)
                        )
                    ),
                    List.of("async-processor-1", "async-processor-2")
                ),
                // Step 3a: Async processor 1 with performance tuning
                new CreatePipelineRequest.StepDefinition(
                    "async-processor-1",
                    "test-echo-module",
                    Map.of(
                        "processorId", "async-1",
                        "simulatedDelay", "100ms",
                        "successRate", "0.95",
                        "concurrency", Map.of(
                            "maxThreads", 10,
                            "queueSize", 100,
                            "keepAliveMs", 60000
                        )
                    ),
                    List.of("result-aggregator")
                ),
                // Step 3b: Async processor 2 with different tuning
                new CreatePipelineRequest.StepDefinition(
                    "async-processor-2",
                    "test-echo-module",
                    Map.of(
                        "processorId", "async-2", 
                        "simulatedDelay", "150ms",
                        "successRate", "0.90",
                        "concurrency", Map.of(
                            "maxThreads", 5,
                            "queueSize", 50,
                            "backpressurePolicy", "drop"
                        )
                    ),
                    List.of("result-aggregator")
                ),
                // Step 4: Result aggregator (fan-in) with complex aggregation logic
                new CreatePipelineRequest.StepDefinition(
                    "result-aggregator",
                    "test-echo-module",
                    Map.of(
                        "aggregationStrategy", "collect-all",
                        "timeoutMs", "5000",
                        "partialResults", "true",
                        "mergeLogic", Map.of(
                            "combineStrategy", "union",
                            "deduplication", true,
                            "sortBy", "timestamp",
                            "maxResults", 1000
                        )
                    ),
                    null // Terminal step
                )
            ),
            List.of("async", "performance", "integration-test"),
            Map.of(
                "purpose", "async-testing",
                "version", "1.0",
                "performanceProfile", "high-throughput"
            )
        );

        HttpRequest<CreatePipelineRequest> httpRequest = HttpRequest.POST("/pipelines?cluster=default", request);
        HttpResponse<PipelineView> response = client.toBlocking().exchange(httpRequest, PipelineView.class);
        
        assertEquals(HttpStatus.CREATED, response.getStatus());
        PipelineView pipeline = response.body();
        assertNotNull(pipeline);
        assertEquals("async-config-pipeline", pipeline.id());
        assertEquals(5, pipeline.steps().size());
        
        LOG.info("Created async configuration pipeline with {} steps", pipeline.steps().size());
        
        // Verify step configurations focus on async processing capabilities
        var inputStep = findStep(pipeline, "input-processor");
        assertEquals("async", inputStep.config().get("processingMode"));
        assertEquals("5", inputStep.config().get("batchSize"));
        assertEquals("30s", inputStep.config().get("timeout"));
        assertEquals("1000", inputStep.config().get("bufferSize"));
        LOG.info("✓ Input step configured for async processing: batch={}, timeout={}", 
                inputStep.config().get("batchSize"), inputStep.config().get("timeout"));
        
        var routerStep = findStep(pipeline, "message-router");
        assertEquals("round-robin", routerStep.config().get("routingStrategy"));
        assertEquals("3", routerStep.config().get("maxRetries"));
        assertNotNull(routerStep.config().get("loadBalancing"));
        LOG.info("✓ Router step configured with load balancing and retries");
        
        // Verify parallel processing steps with different performance profiles
        var processor1 = findStep(pipeline, "async-processor-1");
        var processor2 = findStep(pipeline, "async-processor-2");
        
        assertEquals("async-1", processor1.config().get("processorId"));
        assertEquals("async-2", processor2.config().get("processorId"));
        assertEquals("100ms", processor1.config().get("simulatedDelay"));
        assertEquals("150ms", processor2.config().get("simulatedDelay"));
        
        // Check complex nested concurrency configurations
        assertNotNull(processor1.config().get("concurrency"));
        assertNotNull(processor2.config().get("concurrency"));
        LOG.info("✓ Parallel processors configured with different performance profiles");
        
        // Verify aggregator step with complex merge logic
        var aggregator = findStep(pipeline, "result-aggregator");
        assertEquals("collect-all", aggregator.config().get("aggregationStrategy"));
        assertEquals("5000", aggregator.config().get("timeoutMs"));
        assertEquals("true", aggregator.config().get("partialResults"));
        assertNotNull(aggregator.config().get("mergeLogic"));
        
        @SuppressWarnings("unchecked")
        var mergeLogic = (Map<String, Object>) aggregator.config().get("mergeLogic");
        assertEquals("union", mergeLogic.get("combineStrategy"));
        assertEquals(true, mergeLogic.get("deduplication"));
        assertEquals("timestamp", mergeLogic.get("sortBy"));
        LOG.info("✓ Aggregator configured with complex merge logic: {}", mergeLogic);
        
        // Test pipeline retrieval maintains complex configurations
        HttpRequest<Void> getRequest = HttpRequest.GET("/pipelines/async-config-pipeline?cluster=default");
        HttpResponse<PipelineView> getResponse = client.toBlocking().exchange(getRequest, PipelineView.class);
        assertEquals(HttpStatus.OK, getResponse.getStatus());
        
        PipelineView retrievedPipeline = getResponse.body();
        assertNotNull(retrievedPipeline);
        assertEquals(pipeline.id(), retrievedPipeline.id());
        assertEquals(5, retrievedPipeline.steps().size());
        
        // Verify complex config preservation through serialization/deserialization
        var retrievedRouter = findStep(retrievedPipeline, "message-router");
        assertNotNull(retrievedRouter.config().get("loadBalancing"));
        
        var retrievedAggregator = findStep(retrievedPipeline, "result-aggregator");
        assertNotNull(retrievedAggregator.config().get("mergeLogic"));
        
        LOG.info("✓ Pipeline retrieved with complex configurations intact");
        
        // Test individual step retrieval preserves nested configurations
        HttpRequest<Void> stepRequest = HttpRequest.GET("/pipelines/async-config-pipeline/steps/message-router?cluster=default");
        HttpResponse<PipelineStepView> stepResponse = client.toBlocking().exchange(stepRequest, PipelineStepView.class);
        assertEquals(HttpStatus.OK, stepResponse.getStatus());
        
        PipelineStepView step = stepResponse.body();
        assertNotNull(step);
        assertEquals("message-router", step.id());
        assertEquals("round-robin", step.config().get("routingStrategy"));
        assertNotNull(step.config().get("loadBalancing"));
        
        LOG.info("✓ Individual step retrieval preserves complex configurations");
        
        // Verify pipeline flow connectivity for async processing
        assertTrue(inputStep.next().contains("message-router"), "Input should route to message router");
        assertEquals(2, routerStep.next().size(), "Router should fan out to 2 processors");
        assertTrue(routerStep.next().contains("async-processor-1"));
        assertTrue(routerStep.next().contains("async-processor-2"));
        assertTrue(processor1.next().contains("result-aggregator"), "Processor 1 should route to aggregator");
        assertTrue(processor2.next().contains("result-aggregator"), "Processor 2 should route to aggregator");
        assertTrue(aggregator.next() == null || aggregator.next().isEmpty(), "Aggregator should be terminal");
        
        LOG.info("✓ Pipeline flow verified: input → router → [2 processors] → aggregator");
        
        // Test pipeline tags and async-specific metadata
        assertTrue(pipeline.tags().contains("async"), "Pipeline should have 'async' tag");
        assertTrue(pipeline.tags().contains("performance"), "Pipeline should have 'performance' tag");
        assertTrue(pipeline.tags().contains("integration-test"), "Pipeline should have 'integration-test' tag");
        
        LOG.info("✓ Pipeline tags verified for async processing context");
        
        LOG.info("✅ Async pipeline configuration test passed!");
        LOG.info("   ⚙️  Created 5-step pipeline with complex async configurations");
        LOG.info("   🔀 Verified fan-out/fan-in patterns with different performance profiles"); 
        LOG.info("   🏗️  Confirmed complex nested configuration preservation");
        LOG.info("   🔄 Validated pipeline and step retrieval maintain configuration integrity");
    }
    
    private PipelineView.PipelineStepView findStep(PipelineView pipeline, String stepId) {
        return pipeline.steps().stream()
                .filter(s -> s.id().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Step not found: " + stepId));
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